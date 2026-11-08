# Cloudflare Sandbox 1.0 container image
# Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/replace-the-sandbox-class/
FROM node:24-trixie-slim

RUN apt-get update \
	&& apt-get install -y --no-install-recommends \
		ca-certificates git python3 \
	&& rm -rf /var/lib/apt/lists/*

COPY --from=docker.io/cloudflare/sandbox:1.0.3 /usr/local/bin/sandbox-shim /usr/local/bin/sandbox-shim

WORKDIR /workspace
CMD ["sleep", "infinity"]
