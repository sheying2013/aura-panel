package com.admin.ddns;

import com.admin.ddns.service.DdnsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 在 schema.sql 与既有迁移之后恢复域名映射。
 *
 * <p>Order 放在 ForwardSchemaMigration(0) 之后；新表由 schema.sql 的
 * CREATE TABLE IF NOT EXISTS 保证存在，因此不需要再写 ALTER 迁移。
 */
@Slf4j
@Component
@Order(30)
public class DdnsRegistryBootstrap implements ApplicationRunner {

    private final DdnsService ddnsService;

    public DdnsRegistryBootstrap(DdnsService ddnsService) {
        this.ddnsService = ddnsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ddnsService.loadRegistry()) {
            // 不阻断启动：注册表停在「未就绪」，节点摘要对账这期间只补不删，
            // DdnsScheduler 每一轮都会重试装载，成功后语义自动恢复正常。
            log.warn("DDNS 域名映射未装载，启动后由调度器重试；期间节点摘要对账保守跳过");
        }
    }
}
