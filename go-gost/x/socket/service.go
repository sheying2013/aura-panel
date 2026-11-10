package socket

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/service"
	kill "github.com/go-gost/x/internal/util/port"
	"github.com/go-gost/x/registry"
)

func createServices(req createServicesRequest) error {

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	serviceMutationMux.Lock()
	defer serviceMutationMux.Unlock()

	// config.Global() 返回浅拷贝：Services 切片和 *ServiceConfig 都与全局共享，
	// 这里只把它当读视图，写入一律走 config.OnUpdate。
	cfg := config.Global()

	// 第一阶段：验证所有服务配置并解析
	var created []createdService

	for _, serviceConfig := range req.Data {
		name := strings.TrimSpace(serviceConfig.Name)
		if name == "" {
			return closeCreatedServices(errors.New("service name is required"), created, 0)
		}
		serviceConfig.Name = name

		if registry.ServiceRegistry().IsRegistered(name) {
			return closeCreatedServices(errors.New("service "+name+" already exists"), created, 0)
		}
		// 暂停态服务已经不在注册表里，但配置还在。放行同名创建会让两条配置抢同一个名字，
		// 恢复时谁也说不清该起哪一份。
		if serviceConfigByName(cfg, name) != nil {
			return closeCreatedServices(errors.New("service "+name+" already exists"), created, 0)
		}

		svc, err := parser.ParseService(&serviceConfig)
		if err != nil {
			// 前面几个已经解析好的服务此刻持有已绑定的监听器，必须由本轮调用自己释放。
			return closeCreatedServices(errors.New("create service "+name+" failed: "+err.Error()), created, 0)
		}

		created = append(created, createdService{name: name, service: svc, config: serviceConfig})
	}

	// 第二阶段：注册所有服务
	registered := 0
	for _, cs := range created {
		if err := registry.ServiceRegistry().Register(cs.name, cs.service); err != nil {
			return closeCreatedServices(errors.New("service "+cs.name+" already exists"), created, registered)
		}
		registered++
	}

	// 第三阶段：启动所有服务
	for _, cs := range created {
		go cs.service.Serve()
	}

	// 第四阶段：更新配置。OnUpdate 只改内存里的配置，持久化由调用方负责，也不是原子的。
	config.OnUpdate(func(c *config.Config) error {
		for i := range created {
			serviceConfig := created[i].config
			c.Services = append(c.Services, &serviceConfig)
		}
		return nil
	})

	return nil
}

func updateServices(req updateServicesRequest) error {

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	serviceMutationMux.Lock()
	defer serviceMutationMux.Unlock()

	// 浅拷贝读视图：暂停标记只从快照里读，写入走 config.OnUpdate。
	cfg := config.Global()
	pausedNames := make(map[string]bool, len(req.Data))
	seen := make(map[string]bool, len(req.Data))

	// 第一阶段：验证所有服务存在。暂停态服务没有注册实例，但它仍然在配置里。
	for _, serviceConfig := range req.Data {
		name := strings.TrimSpace(serviceConfig.Name)
		if name == "" {
			return errors.New("service name is required")
		}
		serviceConfig.Name = name

		// 同一个名字带两份配置时哪份生效没有定义，整批拒绝比默默取最后一份安全。
		if seen[name] {
			return errors.New("service " + name + " appears more than once in request")
		}
		seen[name] = true

		if registry.ServiceRegistry().Get(name) != nil {
			continue
		}
		if !isPaused(serviceConfigByName(cfg, name)) {
			return errors.New("service " + name + " not found")
		}
		// 暂停态只允许改配置，不允许顺手把服务拉起来。
		pausedNames[name] = true
	}

	// 第二阶段：按原来的updateService逻辑，逐个更新运行中的服务
	for _, serviceConfig := range req.Data {
		name := serviceConfig.Name
		if pausedNames[name] {
			continue
		}

		// 1./2./3. 关闭旧服务并释放注册名。注册名已被并发替换时不能动别人的实例。
		if !releaseService(name, registry.ServiceRegistry().Get(name)) {
			return errors.New("service " + name + " changed while updating")
		}

		// 4. 解析新服务配置
		svc, err := parser.ParseService(&serviceConfig)
		if err != nil {
			return errors.New("create service " + name + " failed: " + err.Error())
		}

		// 5. 注册新服务
		if err := registry.ServiceRegistry().Register(name, svc); err != nil {
			svc.Close()
			return errors.New("service " + name + " already exists")
		}

		// 6. 启动新服务
		go svc.Serve()
	}

	// 第三阶段：更新配置。只改内存，持久化由调用方负责。
	config.OnUpdate(func(c *config.Config) error {
		for _, serviceConfig := range req.Data {
			name := serviceConfig.Name
			for i := range c.Services {
				if c.Services[i].Name != name {
					continue
				}
				// 运行中的服务不能留暂停标记；暂停态必须带着标记，否则 resume 会把它
				// 判成 not found 或不知情地起个新实例。
				updated := withPausedFlag(serviceConfig, pausedNames[name])
				c.Services[i] = &updated
				break
			}
		}
		return nil
	})

	return nil
}

func deleteServices(req deleteServicesRequest) error {

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	serviceMutationMux.Lock()
	defer serviceMutationMux.Unlock()

	// 浅拷贝读视图：识别暂停态只需要配置里的标记。
	cfg := config.Global()

	// 第一阶段：验证所有服务是否存在
	var servicesToDelete []deletedService
	seen := make(map[string]bool, len(req.Services))

	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}
		// 同一批次重复列出的名字只删一次，避免同一实例被重复关闭。
		if seen[name] {
			continue
		}
		seen[name] = true

		svc := registry.ServiceRegistry().Get(name)
		switch {
		case svc != nil:
			servicesToDelete = append(servicesToDelete, deletedService{name: name, service: svc})
		case isPaused(serviceConfigByName(cfg, name)):
			// 暂停态服务只剩配置：删掉它即可，没有实例要关。
			servicesToDelete = append(servicesToDelete, deletedService{name: name})
		default:
			return errors.New("service " + name + " not found")
		}
	}

	// 第二阶段：删除所有服务。Unregister 自己会 Close 掉实例，
	// 这里再 Close 一次就是双 Close（第二次调用拿到的是已关闭的实例）。
	// 注意 Unregister 只按名字注销、不校验归属，跨包并发替换仍可能被误关。
	for _, std := range servicesToDelete {
		if std.service == nil {
			continue
		}
		registry.ServiceRegistry().Unregister(std.name)
	}

	// 第三阶段：更新配置
	config.OnUpdate(func(c *config.Config) error {
		services := c.Services
		c.Services = nil
		for _, s := range services {
			shouldDelete := false
			for _, std := range servicesToDelete {
				if s.Name == std.name {
					shouldDelete = true
					break
				}
			}
			if !shouldDelete {
				c.Services = append(c.Services, s)
			}
		}
		return nil
	})

	return nil
}

func pauseServices(req pauseServicesRequest) error {

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	serviceMutationMux.Lock()
	defer serviceMutationMux.Unlock()

	// 浅拷贝读视图：Services 切片和 *ServiceConfig 都与全局共享，只做只读判断。
	cfg := config.Global()

	// 第一阶段：验证所有服务是否存在，并筛掉已经暂停的服务
	var servicesToPause []struct {
		name    string
		service service.Service
	}

	seen := make(map[string]bool, len(req.Services))
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}
		// 同一批次重复出现的名字只处理一次：第二次看到的是刚被释放的注册名，
		// 否则会被误判成「并发替换」并把整批回滚掉。
		if seen[name] {
			continue
		}
		seen[name] = true

		svc := registry.ServiceRegistry().Get(name)
		if svc == nil {
			// 已暂停 = 注册名已释放且配置带着暂停标记；此时再 pause 一次是空操作，
			// 不能当成 not found，否则面板重试一次就把整批请求判失败。
			if isPaused(serviceConfigByName(cfg, name)) {
				continue
			}
			return errors.New(fmt.Sprintf("service %s not found", name))
		}

		servicesToPause = append(servicesToPause, struct {
			name    string
			service service.Service
		}{name, svc})
	}

	if len(servicesToPause) == 0 {
		// 整批都已经暂停：不关任何东西，也不写配置。
		return nil
	}

	// 第二阶段：事务性暂停所有服务
	var pausedServices []pausedService

	// 逐个暂停服务，如果失败则回滚
	for _, stp := range servicesToPause {
		serviceConfig := serviceConfigByName(cfg, stp.name)
		if serviceConfig == nil {
			// 找不到配置，回滚已暂停的服务
			return pauseFailure(fmt.Errorf("service %s configuration not found", stp.name), pausedServices)
		}

		// 暂停 = 关闭并释放注册名。若只 Close 而不注销，恢复或回滚时
		// Register 会因名字仍被占而失败，服务再也起不来。
		if !releaseService(stp.name, stp.service) {
			return pauseFailure(fmt.Errorf("service %s changed while pausing", stp.name), pausedServices)
		}

		// 强制断开端口的所有连接
		if serviceConfig.Addr != "" {
			_ = kill.ForceClosePortConnections(serviceConfig.Addr)
		}

		// 记录已暂停的服务
		pausedServices = append(pausedServices, pausedService{stp.name, stp.service, serviceConfig})
	}

	// 第三阶段：更新配置，标记暂停状态。只改内存，持久化由调用方负责。
	err := config.OnUpdate(func(c *config.Config) error {
		for _, stp := range servicesToPause {
			for i := range c.Services {
				if c.Services[i].Name == stp.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
		}
		return nil
	})

	if err != nil {
		// 配置更新失败，需要回滚所有暂停的服务
		return pauseFailure(fmt.Errorf("failed to update config: %v", err), pausedServices)
	}

	return nil
}

func resumeServices(req resumeServicesRequest) error {
	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	serviceMutationMux.Lock()
	defer serviceMutationMux.Unlock()

	// 第一阶段：验证所有服务是否存在，并筛选需要恢复的服务
	var servicesToResume []resumedService
	var skippedServices []string

	cfg := config.Global()
	seen := make(map[string]bool, len(req.Services))
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}
		// 同一批次重复出现的名字只恢复一次：重复那一项会再注册一遍同一个名字。
		if seen[name] {
			continue
		}
		seen[name] = true

		svc := registry.ServiceRegistry().Get(name)

		// 查找配置中的服务
		serviceConfig := serviceConfigByName(cfg, name)
		if serviceConfig == nil {
			return errors.New(fmt.Sprintf("service %s configuration not found", name))
		}

		// 如果服务没有暂停(即正在运行)，跳过
		if !isPaused(serviceConfig) {
			if svc == nil {
				return errors.New(fmt.Sprintf("service %s not found", name))
			}
			skippedServices = append(skippedServices, name)
			continue
		}

		// 已暂停的服务可能早已释放注册名，此时的 nil 是合法状态。
		servicesToResume = append(servicesToResume, resumedService{name, svc, serviceConfig})
	}

	// 第二阶段：事务性恢复所有服务
	var resumedServices []resumedService

	// 逐个恢复服务，如果失败则回滚
	for _, str := range servicesToResume {
		// 先关闭并释放注册名；注册名已被并发替换时不能动别人的实例。
		if str.service != nil {
			if !releaseService(str.name, str.service) {
				return resumeFailure(fmt.Errorf("service %s changed while resuming", str.name), resumedServices)
			}

			// 等待端口释放
			time.Sleep(100 * time.Millisecond)
		}

		// 重新解析并启动服务
		svc, err := parser.ParseService(str.serviceConfig)
		if err != nil {
			// 恢复失败，回滚已恢复的服务
			return resumeFailure(fmt.Errorf("resume service %s failed: %s", str.name, err.Error()), resumedServices)
		}

		if err := registry.ServiceRegistry().Register(str.name, svc); err != nil {
			svc.Close()
			// 恢复失败，回滚已恢复的服务
			return resumeFailure(fmt.Errorf("service %s already exists", str.name), resumedServices)
		}

		go svc.Serve()

		// 记录已成功恢复的服务
		resumedServices = append(resumedServices, resumedService{str.name, svc, str.serviceConfig})
	}

	// 第三阶段：更新配置，移除暂停状态
	err := config.OnUpdate(func(c *config.Config) error {
		for _, str := range servicesToResume {
			for i := range c.Services {
				if c.Services[i].Name == str.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						// 如果 metadata 为空，设置为 nil
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
		}
		return nil
	})

	if err != nil {
		// 配置更新失败，回滚所有已恢复的服务
		return resumeFailure(fmt.Errorf("failed to update config: %v", err), resumedServices)
	}

	return nil
}

// rollbackPausedServices re-registers the services pauseServices already took
// down. Failures are aggregated instead of swallowed: a rollback that leaves a
// service dead is reported to the caller. Caller holds serviceMutationMux.
func rollbackPausedServices(pausedServices []pausedService) error {
	var errs []string
	for _, pss := range pausedServices {
		// 重新解析并启动服务
		svc, err := parser.ParseService(pss.serviceConfig)
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: reparse: %v", pss.name, err))
			continue
		}

		if err := registry.ServiceRegistry().Register(pss.name, svc); err != nil {
			svc.Close()
			errs = append(errs, fmt.Sprintf("%s: re-register: %v", pss.name, err))
			continue
		}

		go svc.Serve()

		// 移除暂停状态标记
		if err := config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == pss.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
			return nil
		}); err != nil {
			errs = append(errs, fmt.Sprintf("%s: clear paused flag: %v", pss.name, err))
		}
	}
	if len(errs) > 0 {
		return errors.New(strings.Join(errs, "; "))
	}
	return nil
}

// rollbackResumedServices puts recovered services back into the paused state:
// released registry name plus the paused flag. Caller holds serviceMutationMux.
func rollbackResumedServices(resumedServices []resumedService) error {
	var errs []string
	for _, rss := range resumedServices {
		// 关闭已恢复的服务并释放注册名。名字已被并发替换时，既不能动别人的实例，
		// 也不能把配置标成暂停：那样 status 会说 paused，实例其实还在收流量。
		if !releaseService(rss.name, rss.service) {
			errs = append(errs, fmt.Sprintf("%s: service was replaced concurrently", rss.name))
			continue
		}

		// 重新标记为暂停状态
		if err := config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == rss.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
			return nil
		}); err != nil {
			errs = append(errs, fmt.Sprintf("%s: mark paused: %v", rss.name, err))
		}
	}
	if len(errs) > 0 {
		return errors.New(strings.Join(errs, "; "))
	}
	return nil
}

// closeCreatedServices releases the services one createServices call built but
// did not hand over: entries below registeredCount were registered by this call
// and are unregistered only while the registry still maps the name to that exact
// instance, the rest were never registered and are simply closed. Instances
// another request owns under the same name are left untouched. Failures are
// appended to the cause, so a create that left a socket bound is not reported as
// a clean failure.
func closeCreatedServices(cause error, created []createdService, registeredCount int) error {
	var errs []string
	for i, cs := range created {
		if i < registeredCount {
			if !releaseService(cs.name, cs.service) {
				errs = append(errs, fmt.Sprintf("%s: service was replaced concurrently", cs.name))
			}
			continue
		}
		if err := cs.service.Close(); err != nil {
			errs = append(errs, fmt.Sprintf("%s: close: %v", cs.name, err))
		}
	}
	if len(errs) > 0 {
		return fmt.Errorf("%w; cleanup incomplete: %s", cause, strings.Join(errs, "; "))
	}
	return cause
}

// serviceMutationMux serializes every registry rewrite this package performs:
// createServices, updateServices, deleteServices, pauseServices and
// resumeServices all hold it for the whole request, so one batch can no longer
// unregister a replacement another batch of this package just registered.
//
// It does not make the registry globally consistent. The registry exposes Get and
// Unregister as two separate operations, so a Register landing between them in
// releaseService can still lose its instance. Other packages rewrite the same
// registry without this lock — go-gost/x/api/config_service.go (create/update/
// delete/pause/resume over HTTP) and go-gost/x/config/loader/loader.go — and a
// caller outside this package can always do that concurrently. Closing that needs
// a compare-and-delete operation in the registry itself, not another mutex here.
var serviceMutationMux sync.Mutex

// pausedService is a service pauseServices closed and unregistered.
type pausedService struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}

// resumedService is a service resumeServices rebuilt and registered.
type resumedService struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}

// createdService is a service createServices parsed itself and still owns.
type createdService struct {
	name    string
	service service.Service
	config  config.ServiceConfig
}

// deletedService is a service deleteServices will drop. service is nil for a
// paused service, which no longer has a registered instance.
type deletedService struct {
	name    string
	service service.Service
}

// serviceConfigByName finds the config entry of name in a config snapshot.
func serviceConfigByName(cfg *config.Config, name string) *config.ServiceConfig {
	if cfg == nil {
		return nil
	}
	for _, s := range cfg.Services {
		if s.Name == name {
			return s
		}
	}
	return nil
}

// isPaused reports whether a config entry carries the paused marker.
func isPaused(cfg *config.ServiceConfig) bool {
	if cfg == nil || cfg.Metadata == nil {
		return false
	}
	paused, _ := cfg.Metadata["paused"].(bool)
	return paused
}

// withPausedFlag returns a copy of cfg carrying the requested paused marker.
// The metadata map is copied so the caller's request document is never mutated.
func withPausedFlag(cfg config.ServiceConfig, paused bool) config.ServiceConfig {
	if cfg.Metadata != nil {
		metadata := make(map[string]any, len(cfg.Metadata))
		for k, v := range cfg.Metadata {
			metadata[k] = v
		}
		cfg.Metadata = metadata
	}
	if paused {
		if cfg.Metadata == nil {
			cfg.Metadata = make(map[string]any, 1)
		}
		cfg.Metadata["paused"] = true
		return cfg
	}
	if cfg.Metadata == nil {
		return cfg
	}
	delete(cfg.Metadata, "paused")
	if len(cfg.Metadata) == 0 {
		cfg.Metadata = nil
	}
	return cfg
}

// releaseService closes svc and frees its registry name, but only while the
// registry still maps name to that exact instance: a service registered
// concurrently under the same name is neither closed nor dropped.
//
// The check is not atomic. registry.Get and registry.Unregister are two
// separate map operations and the API has no compare-and-delete, so a Register
// landing between them can still lose its instance. This narrows the window, it
// does not close it.
func releaseService(name string, svc service.Service) bool {
	if svc == nil || registry.ServiceRegistry().Get(name) != svc {
		return false
	}
	// Unregister closes the instance and drops the name in one step.
	registry.ServiceRegistry().Unregister(name)
	return true
}

// pauseFailure keeps cause primary and appends rollback failures, so a pause
// that left services down is never reported as a clean failure.
func pauseFailure(cause error, pausedServices []pausedService) error {
	if err := rollbackPausedServices(pausedServices); err != nil {
		return fmt.Errorf("%w; rollback incomplete: %v", cause, err)
	}
	return cause
}

// resumeFailure is pauseFailure for the resume direction.
func resumeFailure(cause error, resumedServices []resumedService) error {
	if err := rollbackResumedServices(resumedServices); err != nil {
		return fmt.Errorf("%w; rollback incomplete: %v", cause, err)
	}
	return cause
}

type resumeServicesRequest struct {
	Services []string `json:"services"`
}

type pauseServicesRequest struct {
	Services []string `json:"services"`
}

type deleteServicesRequest struct {
	Services []string `json:"services"`
}

type updateServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}

type createServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}
