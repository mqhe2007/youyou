.PHONY: web-build web-test server-check server-test contract-check docker-build docker-check client-check client-test e2e e2e-trash-docker e2e-android e2e-android-server benchmark acceptance beta-check beta-apk

web-build:
	cd apps/server/web && npm ci && npm run build

web-test:
	cd apps/server/web && npm test

server-check: web-build web-test
	cd apps/server && cargo fmt --all -- --check && cargo check --all-targets --locked && cargo clippy --all-targets --locked -- -D warnings

server-test: web-build
	cd apps/server && cargo test --all-targets --locked

contract-check:
	cd apps/server && cargo test --test openapi_contract --locked

docker-build:
	docker build --file apps/server/Dockerfile --tag youyou-server:dev .

docker-check:
	rm -f /tmp/youyou-server-multiarch.tar
	docker buildx build --platform linux/amd64,linux/arm64 --file apps/server/Dockerfile --tag youyou-server:acceptance --output=type=oci,dest=/tmp/youyou-server-multiarch.tar .

client-check:
	cd apps/android && ./gradlew assembleDebug

client-test:
	cd apps/android && ./gradlew test

e2e: web-build contract-check
	cargo build --manifest-path apps/server/Cargo.toml --locked
	python3 scripts/e2e_http.py

# 回收站真实容器验证：共同挂载内上传/删除/恢复，以及跨挂载点的能力校验。
e2e-trash-docker:
	docker build --file apps/server/Dockerfile --tag youyou-server:delete-regression .
	python3 scripts/e2e_trash_docker.py

# Android E2E 测试（默认走模拟器路径）
# 自动检测/启动模拟器，运行全部 androidTest（UI + 业务功能）
# 用法：
#   make e2e-android                          # 全部测试
#   make e2e-android TESTS=com.example.youyou_album.server.ServerConnectionE2ETest
#   make e2e-android NO_START=1               # 不自动启动模拟器
e2e-android:
	@if [ -n "$(TESTS)" ]; then \
		./scripts/run_e2e_emulator.sh --tests "$(TESTS)"; \
	elif [ -n "$(NO_START)" ]; then \
		./scripts/run_e2e_emulator.sh --no-start; \
	else \
		./scripts/run_e2e_emulator.sh; \
	fi

# 真服务端 Android 验收（连接/双向传输/失败重试/删除/换绑全链路）。
# 与 e2e-android 的区别：这些用例需要 scripts/e2e_android_server.py 注入服务端参数，
# 在 connectedDebugAndroidTest 全量里会被 assumeTrue 静默跳过——发版检查必须走这个目标。
# 前置：本机有 Pixel_10_Pro AVD（或先设 ANDROID_SERIAL 指向已运行模拟器）。
e2e-android-server:
	./scripts/run_e2e_emulator.sh --boot-only
	python3 scripts/e2e_android_server.py

benchmark: web-build
	cargo build --manifest-path apps/server/Cargo.toml --locked
	YOUYOU_BENCHMARK_ENFORCE=1 python3 scripts/acceptance_benchmark.py

acceptance: server-check server-test client-check client-test contract-check e2e benchmark docker-check

beta-check: server-check server-test client-check client-test contract-check e2e e2e-android e2e-android-server

beta-apk:
	cd apps/android && ./gradlew test assembleRelease
