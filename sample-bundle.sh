#!/bin/bash

app_name="${1:-}"
if [ -z "$app_name" ]; then
    echo "Usage: sh sample-bundle.sh <app_name>"
    exit 1
fi

cat <<EOF > index.js
const http = require('http');
const port = process.env.SERVER_PORT || 0;
const server = http.createServer((req, res) => {
    console.log(new Date(), req.method, req.url);
    res.end('Hello from ${app_name}');
});
server.listen(port, () => {
    console.log("Server started on port", port);
});
process.on('SIGTERM', () => process.exit(0));
EOF

tar -czf "${app_name}.tar.gz" index.js
rm index.js
