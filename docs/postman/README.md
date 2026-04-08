# Postman setup for BE-SAM-Editor

## 1) Import files
- Collection: `docs/postman/BE-SAM-Editor.postman_collection.json`
- Environment: `docs/postman/BE-SAM-Editor.local.postman_environment.json`

## 2) Select environment
Trong Postman, chọn environment `BE-SAM-Editor Local`.

## 3) Run backend
```bash
./mvnw spring-boot:run
```
Mặc định API base URL: `http://localhost:8080`.

## 4) Test flow đề xuất (Auth -> Workspace)
1. `Register`
2. Lấy OTP từ email và set vào biến `otpCode`
3. `Verify OTP` (hoặc dùng `Login` nếu tài khoản đã verify)
4. `Workspace > Import GitHub Workspace`
5. `Workspace > Get My Workspaces`
6. `Workspace > Get Workspace Tree`

## 5) Biến môi trường chính
- `autoGenerateEmail=true`: mỗi lần `Register` sẽ tự tạo email mới.
- `repoUrl`: URL repo GitHub public để import.
- `projectId`: tự được set sau khi import hoặc gọi list workspace.
- `accessToken`, `refreshToken`: tự được set sau `Verify OTP` / `Login` / `Refresh Token`.

## 6) Status code thường gặp
- `401`: thiếu/invalid Bearer token
- `404`: repo GitHub không tồn tại hoặc không truy cập được
- `413`: repo chứa thư mục blacklist (`node_modules`, `target`, ...) hoặc vượt giới hạn size
