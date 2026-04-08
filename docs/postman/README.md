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
4. `Me` (kiểm tra access token hiện tại)
5. `Refresh Token` (kiểm tra token rotation, Postman tự so sánh refresh token cũ/mới)
6. Import workspace:
`Workspace > Import GitHub Workspace` (repo public/private qua PAT), hoặc
`Workspace > Import Local Folder Workspace` (folder path ở phía server)
7. `Workspace > Get My Workspaces`
8. `Workspace > Get Workspace Tree`
9. `Logout` hoặc `Logout All`

## 5) Biến môi trường chính
- `autoGenerateEmail=true`: mỗi lần `Register` sẽ tự tạo email mới.
- `repoUrl`: URL repo GitHub public để import.
- `folderPath`: đường dẫn tuyệt đối tới folder source code trên server (ví dụ `/Users/kienroro/projects/sample`).
- `localWorkspaceName`: tên workspace khi import local folder (có thể để trống để dùng tên folder).
- `projectId`: tự được set sau khi import hoặc gọi list workspace.
- `accessToken`, `refreshToken`: tự được set sau `Verify OTP` / `Login` / `Refresh Token`.
- `tokenType`: backend hiện trả `Bearer` (được set tự động khi login/verify/refresh).

## 6) Lưu ý khi chạy auth flow
- `Register` sẽ tự reset `accessToken`, `refreshToken`, `projectId`, `workspaceName` để tránh dùng nhầm session cũ.
- `Refresh Token`, `Logout`, `Logout All` có pre-request check để báo lỗi sớm nếu thiếu token cần thiết.
- `Logout` / `Logout All` sẽ tự clear token trong environment sau khi chạy thành công.

## 7) Status code thường gặp
- `401`: thiếu/invalid Bearer token
- `404`: repo GitHub không tồn tại hoặc không truy cập được
- `413`: source chứa thư mục blacklist (`node_modules`, `target`, ...) hoặc vượt giới hạn size
- `400`: `folderPath` không tồn tại hoặc không phải directory

## 8) Tránh GitHub rate limit
- Set biến môi trường backend `APP_GITHUB_TOKEN=<your_pat>` trước khi chạy app.
- Có thể thêm trực tiếp vào file `.env` để backend tự nạp.
