# Docker Runtime

Thư mục này dùng để chạy backend đã publish trên Docker Hub mà không cần source code backend.

## Files

- `docker-compose.yml`: chạy `backend` và `mysql`
- `.env.example`: biến môi trường mẫu

## Run

```bash
cp .env.example .env
docker compose up -d
```

Nếu chỉ muốn chạy thử nhanh, bạn có thể bỏ qua bước tạo `.env`. File compose đã có demo defaults.

## Update Image Tag

Đổi image hoặc version trong `.env`:

```bash
BACKEND_IMAGE_NAME=kienroro/be-sam-editor-backend
BACKEND_IMAGE_TAG=latest
```

Ví dụ chạy một version cụ thể:

```bash
BACKEND_IMAGE_TAG=v1.0.1
docker compose up -d
```

## Verify

```bash
docker compose ps
docker compose logs -f backend
```

Backend mặc định mở cổng `8080`.

## Stop

```bash
docker compose down
```

Xóa luôn data MySQL và workspace:

```bash
docker compose down -v
```
