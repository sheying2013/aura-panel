package socket

import (
	"os"
	"path/filepath"
)

// TestMain in compatibility_helpers_test.go requires FLUX_COMPAT_TEST_ROOT.
// The documented runner (testdata/socket-fixture/exec.sh) starts the test binary
// from that fixture directory, so derive the root from there and drop the need
// to export the variable by hand. Anywhere else nothing is set and the original
// requirement still applies.
func init() {
	if os.Getenv("FLUX_COMPAT_TEST_ROOT") != "" {
		return
	}
	wd, err := os.Getwd()
	if err != nil {
		return
	}
	if _, err := os.Stat(filepath.Join(wd, "exec.sh")); err != nil {
		return
	}
	if _, err := os.Stat(filepath.Join(wd, "config.json")); err != nil {
		return
	}
	_ = os.Setenv("FLUX_COMPAT_TEST_ROOT", wd)
}
