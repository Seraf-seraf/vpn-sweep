package media

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const ytDLPVersion = "2026.8.19"
const ejsVersion = "0.8.0"
const minNodeMajor = 24
const setupTimeout = 10 * time.Minute

func Prepare(ctx context.Context, dir string, expected *model.ResolverIdentity, notify func(string)) (*Resolver, error) {
	if ctx.Err() != nil {
		return nil, setupError(ctx, "youtube_setup_failed")
	}
	ctx, cancel := context.WithTimeout(ctx, setupTimeout)
	defer cancel()
	dir, err := private.Dir(dir)
	if err != nil {
		return nil, err
	}
	lock, err := private.Open(filepath.Join(dir, "setup.lock"), os.O_CREATE|os.O_RDWR)
	if err != nil {
		return nil, err
	}
	defer lock.Close()
	if err = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		return nil, model.Err("youtube_setup_locked", "setup")
	}
	log := func(message string) {
		if notify != nil {
			notify(message)
		}
	}
	r := &Resolver{Python: filepath.Join(dir, "venv", "bin", "python")}
	r.JS = runtimePath("node")
	if id, err := r.Identify(ctx); err == nil {
		if expected != nil && id != *expected {
			return nil, model.Err("resolver_identity_changed", "setup")
		}
		r.Identity = id
		return r, nil
	}
	if ctx.Err() != nil {
		return nil, setupError(ctx, "youtube_setup_failed")
	}
	if expected != nil {
		return nil, model.Err("resolver_identity_changed", "setup")
	}
	python := runtimePath("python3")
	if python == "" {
		python = runtimePath("python")
	}
	if python == "" || !nodeSupported(ctx, r.JS) {
		if ctx.Err() != nil {
			return nil, setupError(ctx, "youtube_setup_failed")
		}
		pkg, err := exec.LookPath("pkg")
		if err != nil {
			return nil, setupError(ctx, "youtube_runtime_unavailable")
		}
		log("Подготовка YouTube: установка Python и Node.js")
		if err = runProcess(ctx, exec.Command(pkg, "install", "-y", "python", "nodejs-lts")); err != nil {
			return nil, setupError(ctx, "youtube_runtime_install_failed")
		}
		python = runtimePath("python3")
		if python == "" {
			python = runtimePath("python")
		}
		r.JS = runtimePath("node")
		if python == "" || !nodeSupported(ctx, r.JS) {
			return nil, setupError(ctx, "youtube_runtime_unavailable")
		}
	}
	venv := filepath.Dir(filepath.Dir(r.Python))
	if st, err := os.Lstat(venv); err == nil && !st.IsDir() {
		return nil, model.Err("unsafe_directory", "setup")
	} else if err != nil && !os.IsNotExist(err) {
		return nil, model.Err("directory_unavailable", "setup")
	}
	log("Подготовка YouTube: создание окружения")
	if err = runProcess(ctx, exec.Command(python, "-I", "-m", "venv", venv)); err != nil {
		return nil, setupError(ctx, "youtube_environment_install_failed")
	}
	log("Подготовка YouTube: установка зависимостей")
	cmd := exec.Command(r.Python, "-I", "-m", "pip", "--isolated", "install", "--no-input", "--disable-pip-version-check", "--index-url", "https://pypi.org/simple", "yt-dlp=="+ytDLPVersion, "yt-dlp-ejs=="+ejsVersion)
	if err = runProcess(ctx, cmd); err != nil {
		return nil, setupError(ctx, "youtube_dependencies_install_failed")
	}
	id, err := r.Identify(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return nil, setupError(ctx, "youtube_setup_failed")
		}
		return nil, err
	}
	r.Identity = id
	log("Подготовка YouTube завершена")
	return r, nil
}

func runtimePath(name string) string {
	path, err := exec.LookPath(name)
	if err != nil {
		return ""
	}
	path, err = filepath.Abs(path)
	if err != nil {
		return ""
	}
	return path
}

func nodeSupported(ctx context.Context, path string) bool {
	if path == "" {
		return false
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	var out limitedOutput
	cmd := exec.Command(path, "--version")
	cmd.Stdout = &out
	if runProcess(ctx, cmd) != nil {
		return false
	}
	major, err := strconv.Atoi(strings.Split(strings.TrimPrefix(strings.TrimSpace(out.String()), "v"), ".")[0])
	return err == nil && major >= minNodeMajor
}

func setupError(ctx context.Context, code string) error {
	if ctx.Err() == context.Canceled {
		return model.Err("interrupted", "setup")
	}
	if ctx.Err() == context.DeadlineExceeded {
		return model.Err("youtube_setup_timeout", "setup")
	}
	return model.Err(code, "setup")
}
