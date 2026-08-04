# B Dịch — Android 0.1.3

Bản Android đầu tiên của ứng dụng dịch chữ trực tiếp trên màn hình.

## Đã có trong dự án

- Nút **B** nổi trên màn hình chính và trên ứng dụng khác.
- Kéo thả tự do, tự bám mép và mờ đi khi không dùng.
- Bấm B khi chưa xác thực: bảng **Đăng nhập / Đăng ký**.
- Dùng chung tài khoản qua Supabase Edge Function `account-api`.
- **Tắt B** dừng chương trình nhưng giữ phiên đăng nhập đã mã hóa.
- Nhấp đúp nút B (chạm nhanh 2 lần) để dịch một lần.
- Chạm một lần để mở bảng điều khiển.
- Nút **Đóng bảng** nằm ở cuối bảng; nút **Tắt B** bên dưới dùng để thoát chương trình.
- Chạm rồi kéo chỉ di chuyển nút B, không kích hoạt dịch.
- Chế độ dịch liên tục không cần thao tác với nút B.
- Chụp màn hình bằng `MediaProjection`.
- OCR ML Kit cho chữ Latin, Trung, Nhật và Hàn.
- Dịch ngoại tuyến bằng ML Kit Translation sau khi tải mô hình lần đầu.
- Chữ dịch phủ đúng vị trí chữ gốc, có nền tối mờ.
- Tự cập nhật vùng chụp khi xoay dọc/ngang.

## Cấu hình trước khi chạy

1. Cài Android Studio và Android SDK 35.
2. Sao chép `local.properties.example` thành `local.properties`.
3. Sửa `sdk.dir` theo thư mục Android SDK trên máy.
4. URL Edge Function của dự án đã được điền sẵn:

   ```properties
   ACCOUNT_API_URL=https://slmyzhdnkwjkdwgwjhuh.supabase.co/functions/v1/b-dich-api
   ```

   `b-dich-api` dùng chung bảng tài khoản và phiên đăng nhập với `account-api`, nhưng được triển khai riêng cho ứng dụng B Dịch. Không đưa `SUPABASE_SERVICE_ROLE_KEY` vào ứng dụng.

   Nếu triển khai bằng Supabase CLI, dự án đã có sẵn `supabase/config.toml` với:

   ```toml
   [functions.b-dich-api]
   verify_jwt = false
   ```

5. Mở thư mục dự án bằng Android Studio, chờ Gradle Sync (Gradle 8.11.1) rồi chạy trên điện thoại Android 8.0 trở lên.

## Lần mở đầu tiên

1. Cho phép **Hiển thị trên ứng dụng khác**.
2. Bật nút B.
3. Chạm B một lần và đăng nhập hoặc đăng ký.
4. Chọn hai ngôn ngữ.
5. Nhấp đúp B để dịch. Trong lần dịch đầu tiên, Android sẽ hỏi quyền chụp màn hình; chọn **Bắt đầu ngay**.
6. ML Kit tải mô hình dịch lần đầu. Các lần sau có thể dịch ngoại tuyến.

## Lưu ý

- Android luôn hiện thông báo nhỏ khi nút B/chụp màn hình hoạt động; đây là yêu cầu của hệ điều hành.
- Ứng dụng ngân hàng, màn hình DRM hoặc nội dung dùng `FLAG_SECURE` có thể trả ảnh đen và không cho dịch.
- Bản này dịch chữ hiển thị trên màn hình. Dịch lời thoại chỉ có âm thanh sẽ cần thêm nhận dạng giọng nói ở giai đoạn sau.
- Không đặt Supabase service-role key trong ứng dụng. Ứng dụng chỉ gọi URL Edge Function công khai; khóa bí mật vẫn nằm trong biến môi trường của Supabase.
