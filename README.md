# BE-SAM-Editor

## Docker (Backend)

### 1) Run backend + MySQL from the published image

```bash
docker compose -f docker-compose.backend.yml up -d
```

By default, Compose pulls `kienroro2003/be-sam-editor-backend:latest`.
You can override the image or tag with environment variables:

```bash
BACKEND_IMAGE_NAME=your-dockerhub-user/be-sam-editor-backend \
BACKEND_IMAGE_TAG=v1.0.0 \
docker compose -f docker-compose.backend.yml up -d
```

Compose can start without a `.env` file by using built-in demo defaults.
If you need real secrets or service credentials, copy `.env.example` to `.env` and edit the values you need:

```bash
cp .env.example .env
```

### 2) Build locally from source

```bash
docker compose -f docker-compose.backend.yml up -d --build
```

### 3) Run backend container only (DB outside)

```bash
docker build -t be-sam-editor-backend .

docker run --rm -p 8080:8080 \
  -e DB_URL='jdbc:mysql://host.docker.internal:3306/sam_editor_db?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC' \
  -e DB_USERNAME='root' \
  -e DB_PASSWORD='your-password' \
  -e APP_OAUTH2_REDIRECT_URI='http://localhost:3000/oauth2/success' \
  -e GITHUB_CLIENT_ID='your-github-client-id' \
  -e GITHUB_CLIENT_SECRET='your-github-client-secret' \
  be-sam-editor-backend
```

### 4) Push the image to Docker Hub manually

```bash
docker login

docker build -t be-sam-editor-backend .
docker tag be-sam-editor-backend kienroro2003/be-sam-editor-backend:latest
docker push kienroro2003/be-sam-editor-backend:latest
```

For a versioned release:

```bash
docker tag be-sam-editor-backend kienroro2003/be-sam-editor-backend:v1.0.0
docker push kienroro2003/be-sam-editor-backend:v1.0.0
```

### 5) Publish to Docker Hub automatically with GitHub Actions

Configure these repository settings before pushing `main`, `master`, or a `v*` tag:

- Repository secret: `DOCKERHUB_TOKEN`
- Repository variable: `DOCKERHUB_USERNAME` (defaults to `kienroro2003`)
- Repository variable: `DOCKERHUB_REPOSITORY` (defaults to `be-sam-editor-backend`)

When those are configured, the workflow will:

- run Maven tests
- build the Spring Boot jar
- upload the jar artifact
- build and push the Docker image to Docker Hub

Stop the local stack:

```bash
docker compose -f docker-compose.backend.yml down
```
