# Proxy Configuration Guide

This guide explains how to configure HTTPS proxy with authentication for the prediction markets data fetchers.

## Overview

The WebClient used by Kalshi and Polymarket fetchers supports HTTPS proxy with username/password authentication. This is useful when:

- Running behind a corporate proxy
- Using a proxy service for IP rotation
- Needing to route traffic through a specific network

## Configuration Methods

### Method 1: Environment Variables (Recommended)

Set the following environment variables before starting the application:

```bash
export PROXY_HOST=proxy.example.com
export PROXY_PORT=8080
export PROXY_USERNAME=your_username
export PROXY_PASSWORD=your_password
```

Then run the application:

```bash
./gradlew bootRun
```

### Method 2: .env File (Development)

1. Copy the sample file:
   ```bash
   cp .env.sample .env
   ```

2. Edit `.env` and uncomment/fill in the proxy settings:
   ```properties
   PROXY_HOST=proxy.example.com
   PROXY_PORT=8080
   PROXY_USERNAME=your_username
   PROXY_PASSWORD=your_password
   ```

3. Run the application (spring-dotenv automatically loads `.env`)

### Method 3: System Properties

Pass as JVM arguments:

```bash
./gradlew bootRun -Dproxy.host=proxy.example.com -Dproxy.port=8080 \
  -Dproxy.username=your_username -Dproxy.password=your_password
```

## Environment Variables Reference

| Variable | Description | Required | Example |
|----------|-------------|----------|---------|
| `PROXY_HOST` | Proxy server hostname or IP | No | `proxy.example.com` |
| `PROXY_PORT` | Proxy server port | No | `8080` |
| `PROXY_USERNAME` | Proxy authentication username | No | `user123` |
| `PROXY_PASSWORD` | Proxy authentication password | No | `password123` |

**Note:** All proxy settings are optional. If not provided, the application uses direct connections.

## Authentication

The proxy configuration supports **Basic Authentication**:

- Username and password are Base64-encoded
- Sent via `Proxy-Authorization` header
- Works with most HTTP/HTTPS proxy servers

## How It Works

1. **No Proxy**: If `PROXY_HOST` or `PROXY_PORT` is not set, direct connection is used
2. **Proxy Without Auth**: If only host/port are set, unauthenticated proxy is used
3. **Proxy With Auth**: If all four variables are set, authenticated proxy is used

The WebClient is configured in `WebClientConfig.java` to:
- Use Netty's `ProxyProvider` for HTTP proxy
- Add Basic Authentication headers if credentials are provided
- Set a 30-second response timeout

## Logging

The application logs proxy configuration at startup:

```
INFO  c.e.a.p.config.WebClientConfig - Configuring HTTPS proxy: proxy.example.com:8080
INFO  c.e.a.p.config.WebClientConfig - Proxy authentication configured for user: user123
```

Or if no proxy:

```
INFO  c.e.a.p.config.WebClientConfig - No proxy configuration found, using direct connection
```

## Testing Proxy Configuration

1. Configure proxy settings
2. Start the application
3. Manually trigger a fetch:
   ```bash
   curl -X POST http://localhost:8080/api/prediction-markets/fetch/kalshi
   ```
4. Check logs for proxy connection messages
5. Verify data is fetched successfully

## Docker Deployment

When using Docker, pass environment variables:

```bash
docker run -e PROXY_HOST=proxy.example.com \
           -e PROXY_PORT=8080 \
           -e PROXY_USERNAME=user \
           -e PROXY_PASSWORD=pass \
           your-image:latest
```

Or use docker-compose:

```yaml
services:
  backend:
    image: your-image:latest
    environment:
      - PROXY_HOST=proxy.example.com
      - PROXY_PORT=8080
      - PROXY_USERNAME=user
      - PROXY_PASSWORD=pass
```

## Kubernetes Deployment

Create a Secret:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: proxy-credentials
type: Opaque
stringData:
  PROXY_HOST: proxy.example.com
  PROXY_PORT: "8080"
  PROXY_USERNAME: user
  PROXY_PASSWORD: password
```

Reference in Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trading-desk-backend
spec:
  template:
    spec:
      containers:
      - name: backend
        image: your-image:latest
        envFrom:
        - secretRef:
            name: proxy-credentials
```

## Security Notes

⚠️ **Important Security Considerations:**

1. **Never commit `.env` files** - they are in `.gitignore`
2. **Never commit proxy passwords** in code or config files
3. **Use secrets management** in production (e.g., HashiCorp Vault, AWS Secrets Manager)
4. **Rotate proxy credentials** regularly
5. **Use HTTPS proxies** for encrypted traffic
6. **Restrict proxy access** to only necessary services

## Troubleshooting

### Connection Refused

```
java.net.ConnectException: Connection refused
```

**Solution**: Check that `PROXY_HOST` and `PROXY_PORT` are correct and the proxy is reachable.

### Authentication Failed (407)

```
reactor.netty.http.client.PrematureCloseException: Connection prematurely closed BEFORE response
```

**Solution**: Verify `PROXY_USERNAME` and `PROXY_PASSWORD` are correct.

### Timeout

```
java.util.concurrent.TimeoutException: Did not observe any item or terminal signal within 30000ms
```

**Solution**:
- Check proxy is not blocking the target URLs (Kalshi/Polymarket APIs)
- Increase timeout in `WebClientConfig.java` if needed
- Verify network connectivity through the proxy

### SSL/TLS Issues

If you encounter SSL certificate issues:

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed
```

**Solution**: The proxy may be intercepting SSL. You may need to import the proxy's SSL certificate into Java's truststore (not recommended for production).

## Example Configurations

### Corporate Proxy (with authentication)

```bash
export PROXY_HOST=corporate-proxy.company.com
export PROXY_PORT=8080
export PROXY_USERNAME=employee.name
export PROXY_PASSWORD=SecurePassword123
```

### SOCKS5 Proxy (Note: Current implementation is HTTP proxy only)

The current implementation uses HTTP proxy. For SOCKS5 support, modify `WebClientConfig.java` to use `ProxyProvider.Proxy.SOCKS5`.

### No Proxy (Direct Connection)

Simply don't set the environment variables, or set them to empty:

```bash
unset PROXY_HOST
unset PROXY_PORT
unset PROXY_USERNAME
unset PROXY_PASSWORD
```

## Advanced Configuration

### Per-Environment Configuration

Create environment-specific .env files:

- `.env.development` - Development proxy
- `.env.staging` - Staging proxy
- `.env.production` - Production proxy (should use secrets manager instead)

Load the appropriate file based on `SPRING_PROFILES_ACTIVE`.

### Bypass Proxy for Localhost

If you need to bypass proxy for local development while keeping it for external APIs, you can modify `WebClientConfig.java` to add exclusion logic.

## Related Files

- `WebClientConfig.java` - Main proxy configuration
- `application.properties` - Default property mappings
- `.env.sample` - Template for environment variables
- `.gitignore` - Excludes .env files from version control
