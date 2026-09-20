.PHONY: web-build server-check server-test contract-check docker-build docker-check client-check client-test e2e e2e-android benchmark acceptance beta-check beta-apk

web-build:
	cd apps/server/web && npm ci && npm run build

server-check: web-build
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

benchmark: web-build
	cargo build --manifest-path apps/server/Cargo.toml --locked
	YOUYOU_BENCHMARK_ENFORCE=1 python3 scripts/acceptance_benchmark.py

acceptance: server-check server-test client-check client-test contract-check e2e benchmark docker-check

beta-check: server-check server-test client-check client-test contract-check e2e e2e-android

beta-apk:
	cd apps/android && ./gradlew test assembleRelease
