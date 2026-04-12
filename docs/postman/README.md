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

## 4) Test flow đề xuất (Auth -> Workspace -> Analysis)
1. `Register`
2. Lấy OTP từ email và set vào biến `otpCode`
3. `Verify OTP` (hoặc dùng `Login` nếu tài khoản đã verify)
4. `Me` (kiểm tra access token hiện tại)
5. `Refresh Token` (kiểm tra token rotation, Postman tự so sánh refresh token cũ/mới)
6. Import workspace:
`Workspace > Import GitHub Workspace` (repo public/private qua PAT), hoặc
`Workspace > Import Local Folder Workspace` (upload file `.zip` từ client)
7. `Workspace > Get My Workspaces`
8. `Workspace > Get Workspace Tree`
9. Set biến `workspaceFilePath` (ví dụ `src/main/App.java`) theo tree vừa nhận
10. `Workspace > Get Workspace File Content`
11. Analysis theo ngôn ngữ:
    - **Java:** Set `analysisJavaFilePath` rồi gọi `Analysis > Analyze Java File`
    - **JS/TS:** Set `analysisJsFilePath` rồi gọi `Analysis > Analyze JS/TS File`
    - **Auto-detect:** Set `analysisFilePath` rồi gọi `Analysis > Analyze File (Auto-detect Language)`
12. `Analysis > Get Function Summaries`
13. Copy `functionId` từ response rồi set tay vào environment
14. `Analysis > Get Function CFG`
15. Coverage theo ngôn ngữ:
    - **Java:** `Analysis > Run Java Coverage`
    - **Auto-detect:** Set `analysisFilePath` rồi gọi `Analysis > Run Coverage (Auto-detect Language)`
16. (Tuỳ chọn) `Workspace > Delete Workspace Folder` với `workspaceFolderPath`
17. (Tuỳ chọn) `Workspace > Delete Workspace`
18. `Logout` hoặc `Logout All`

## 5) Biến môi trường chính
- `autoGenerateEmail=true`: mỗi lần `Register` sẽ tự tạo email mới.
- `repoUrl`: URL repo GitHub public để import.
- `localWorkspaceZipPath`: đường dẫn local tới file `.zip` khi dùng Postman form-data.
- `localWorkspaceName`: tên workspace khi import local zip (có thể để trống để dùng tên file zip).
- `projectId`: tự được set sau khi import hoặc gọi list workspace.
- `workspaceFilePath`: path file tương đối trong workspace dùng cho API đọc nội dung file.
- `workspaceFolderPath`: path folder tương đối trong workspace dùng cho API xóa folder.
- `functionId`: set thủ công sau khi đọc response từ `Analyze Java File`, `Analyze JS/TS File`, hoặc `Get Function Summaries`.
- `analysisJsFilePath`: path file JS/TS tương đối trong workspace (ví dụ `src/utils/helper.js`, `src/App.tsx`).
- `analysisFilePath`: path file bất kỳ dùng cho endpoint auto-detect language (`.java`, `.js`, `.ts`, `.py`, v.v.).
- `analysisPath`, `analysisLanguage`, `analysisCached`: được set tự động sau khi chạy request analysis thành công.
- `analysisFunctionName`: được set tự động sau khi lấy CFG thành công.
- `accessToken`, `refreshToken`: tự được set sau `Verify OTP` / `Login` / `Refresh Token`.
- `tokenType`: backend hiện trả `Bearer` (được set tự động khi login/verify/refresh).

## 6) Lưu ý khi chạy auth flow
- `Register` sẽ tự reset `accessToken`, `refreshToken`, `projectId`, `workspaceName` để tránh dùng nhầm session cũ.
- `Refresh Token`, `Logout`, `Logout All` có pre-request check để báo lỗi sớm nếu thiếu token cần thiết.
- `Logout` / `Logout All` sẽ tự clear token trong environment sau khi chạy thành công.

## 7) Status code thường gặp
- `401`: thiếu/invalid Bearer token
- `404`: repo GitHub không tồn tại hoặc không truy cập được
- `413`: tổng dung lượng source hợp lệ vượt giới hạn size
- `400`: zip không hợp lệ hoặc không đúng định dạng `.zip`
- `413`: nội dung file vượt `app.workspace.file-content-max-bytes` khi gọi API đọc file
- `400`: analysis chỉ hỗ trợ file `JAVA`, `JAVASCRIPT`, `TYPESCRIPT`; path không hợp lệ; file quá lớn; file không phải UTF-8; hoặc source có lỗi cú pháp
- `404`: workspace/file/function analysis không tồn tại, chưa có cache analysis, hoặc cache analysis đã stale

## 8) Quy tắc blacklist khi import workspace
- Với `Import Local Folder Workspace` (upload zip), các path segment blacklist (`.git`, `node_modules`, `target`, `dist`, `build`, `.idea`, `.vscode`) sẽ bị bỏ qua khi index source files.
- Với `Import GitHub Workspace`, các path blacklist được bỏ qua khi index source files.
- Kích thước source hợp lệ không được vượt `app.workspace.max-size-bytes`.

## 9) Tránh GitHub rate limit
- Set biến môi trường backend `APP_GITHUB_TOKEN=<your_pat>` trước khi chạy app.
- Có thể thêm trực tiếp vào file `.env` để backend tự nạp.

## 10) Ghi chú cho Analysis API
- `Analyze Java File`, `Analyze JS/TS File`, và `Analyze File (Auto-detect Language)` sẽ tự phân tích lại nếu nội dung file đã thay đổi; nếu cache còn hợp lệ thì response có `cached=true`.
- `Get Function Summaries` và `Get Function CFG` không tự phân tích lại; nếu file đã đổi sau lần phân tích trước, backend sẽ báo cache stale.
- Analysis hỗ trợ: `JAVA`, `JAVASCRIPT` (`.js`, `.jsx`), `TYPESCRIPT` (`.ts`, `.tsx`).
- `Analyze JS/TS File` (endpoint `/analysis/js`) dùng tree-sitter để build Control Flow Graph và tính Cyclomatic Complexity cho JavaScript/TypeScript.
- `Analyze File (Auto-detect Language)` (endpoint `/analysis/file`) tự detect ngôn ngữ từ extension file trong workspace.

## 11) Ghi chú cho Coverage API
- `Run Java Coverage` (endpoint `/analysis/java/coverage`) chỉ hỗ trợ file Java, chạy JaCoCo qua Docker sandbox.
- `Run Coverage (Auto-detect Language)` (endpoint `/analysis/coverage`) tự detect ngôn ngữ:
  - **Java:** JaCoCo XML report (Maven + JaCoCo plugin)
  - **JavaScript/TypeScript:** LCOV report (Jest/Vitest)
  - **Python:** Cobertura XML report (pytest-cov)
- Coverage chạy trong Docker sandbox nên cần Docker daemon running.
