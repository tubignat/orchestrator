# Node.js App Orchestrator

A tiny REST service that deploys and runs simple Node.js HTTP apps and routes traffic to them by subdomain. It expects a .tar.gz bundle with an `index.js` entry point.

## Getting started

### Quick demo

The repo includes a helper script with a quick demo. It pulls the latest image, starts a service container, and deploys two sample apps.

```
sh test.sh
```

### Run the image manually

```
docker build -t tubignat/orchestrator:latest .
docker run p- 80:8080 -e DOMAIN=kineto.local tubignat/orchestrator:latest
```

Use -e DOMAIN=... to set the base domain for subdomain routing. The default is localhost. Routing to subdomains via localhost (i.e. http://my-app.localhost/) works in Chrome and Firefox, but not Safari.

## Overview

### Features

- Deploy and run Node.js apps
- Route traffic to apps by subdomain
- Revive crashed apps, detect crash-loops
- Track app status and resource usage
- Enforce CPU and memory limits per app
- Retrieve apps' logs

### API

- **POST /deploy?name=app-name**. Body: app bundle in a .tar.gz format, must include `index.js` at the root. Deploys a new app or upgrades an existing one.
- **POST /start?name=app-name**. Starts an app.
- **POST /stop?name=app-name**. Stops an app.
- **GET /status?name=app-name**. Returns current status of the app and its resource usage, i.e. `RUNNING | mem: 42MiB | cpu: 1.2%`.
- **GET /logs?name=<app>[&limit=50]**. Returns the last `limit` lines of the app's log.

### High-level architecture

![Architecture diagram](https://github.com/tubignat/orchestrator/blob/main/diagram.png?raw=true)

**App Manager**. Keeps track of apps' lifecycles: sends start/stop signals to App Runner, restarts crashed apps, detects crash-loops.<br>
**App Runner**. Abstracts away handling OS processes, enforces resource usage limits. <br>
**REST Controller**. Converts HTTP entities into internals and back<br>
**Proxy**. Decides whether to route traffic to the orchestrator or to the app itself.

## Limitations and future improvements

### Isolation

Currently, apps are run as local OS processes. Isolation must be added for safety, either via containerization
(easy to implement but might add overhead especially for a fleet of very small apps) or via OS methods (cgroups, seccomp, etc.)

### Resource consumption

On my machine, even the smallest apps consume 20-50 MiB and 0.5-1% of CPU. This creates a limit to how much the system can scale. Possible solutions:

- Shutdown rarely used app, start them back up on demand. Small Node.js apps typically start within tens or hundreds of milliseconds, which is a reasonable tradeoff.
- Explore using an alternative engine. Most notably, Bun and Deno, which are designed to be Node.js compatible and in my own tests show improvements for small apps: ~20% smaller memory footprint and ~30% faster startup time.
- If it is feasible to move to a different technology stack. I.e. if users aren't editing JS code directly, there's no need to use Node.js. In small apps, languages that don't have GC and compile to native code might have a 
smaller memory footprint and faster startup time, which in turn would allow serving most of the requests from cold apps.

### Missing features

- Collect memory dumps on app crashes for inspection
- Expose per-app metrics: resource usage, startup latency, request latencies, Node.js internal metrics like GC, event loop delays, etc. 
- Custom domains for apps
- Persist AppManager state so it is possible to redeploy the orchestrator itself without restarting the apps
- Limit disk usage and network I/O, not just CPU and memory, enforce rate limits for incoming HTTP requests
- Avoid app downtime during redeployments: start a new app version alongside the old one, then switch traffic, then decommission the old one.
- Support WebSocket connection for the apps
