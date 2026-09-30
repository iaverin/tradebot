## Request with authorization

```shell
curl -v -s -X GET http://localhost:8080/api/prediction-markets/fetch/next-cron-time \
    -H "Authorization: Bearer $(
        curl -s -X POST http://localhost:8080/auth/login \
            -H "Content-Type: application/json"  \
            -d '{"username": "login", "password": "pass"}' \
            | jq -r '.token')"
```

