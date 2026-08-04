# B Dịch — Android 0.1.4

Bản Android đầu tiên của ứng dụng dịch chữ trực tiếp trên màn hình.

## Đã có trong dự án

- Nút **B** nổi trên màn hình chính và trên ứng dụng khác.
- Kéo thả tự do, tự bám mép và mờ đi khi không dùng.
- Chạm B khi chưa xác thực: bảng **Đăng nhập / Đăng ký**.
- Dùng chung tài khoản qua Supabase Edge Function `account-api`.
- **Tắt B** dừng chương trình nhưng giữ phiên đăng nhập đã mã hóa.
- Chạm nút B một lần để dịch một lần.
- Giữ nút B để mở bảng; nếu bảng đang mở thì giữ B để đóng.
- Nút **Đóng bảng** nằm ở cuối bảng; nút **Tắt B** bên dưới dùng để thoát chương trình.
- Chạm rồi kéo chỉ di chuyển nút B, không kích hoạt dịch.
- Khi chữ dịch đang hiện, chạm bất kỳ chỗ nào ngoài B để xóa chữ dịch; lần chạm đó không bấm xuyên xuống ứng dụng bên dưới.
- Chế độ dịch liên tục không cần thao tác với nút B.
- Chụp màn hình bằng `MediaProjection`.
- OCR ML Kit cho chữ Latin, Trung, Nhật và Hàn.
- Dịch ngoại tuyến bằng ML Kit Translation sau khi tải mô hình lần đầu.
- Chữ dịch phủ đúng vị trí chữ gốc, có nền tối mờ.
- Tự cập nhật vùng chụp khi xoay dọc/ngang.
- Kiểm tra `https://boi-ungdung1.pages.dev/version.json`, tải APK mới và mở màn hình cài đặt Android ngay trong ứng dụng.
- Avatar xanh–tím riêng của B Dịch, có logo BOI của nhà phát hành ở góc trái dưới.

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
3. Giữ B để mở bảng và đăng nhập hoặc đăng ký.
4. Chọn hai ngôn ngữ.
5. Chạm B một lần để dịch. Trong lần dịch đầu tiên, Android sẽ hỏi quyền chụp màn hình; chọn **Bắt đầu ngay**.
6. ML Kit tải mô hình dịch lần đầu. Các lần sau có thể dịch ngoại tuyến.

## Lưu ý

- Android luôn hiện thông báo nhỏ khi nút B/chụp màn hình hoạt động; đây là yêu cầu của hệ điều hành.
- Ứng dụng ngân hàng, màn hình DRM hoặc nội dung dùng `FLAG_SECURE` có thể trả ảnh đen và không cho dịch.
- Bản này dịch chữ hiển thị trên màn hình. Dịch lời thoại chỉ có âm thanh sẽ cần thêm nhận dạng giọng nói ở giai đoạn sau.
- Không đặt Supabase service-role key trong ứng dụng. Ứng dụng chỉ gọi URL Edge Function công khai; khóa bí mật vẫn nằm trong biến môi trường của Supabase.

## Phát hành bản cập nhật

Mọi APK từ 0.1.4 trở đi phải được ký bằng cùng khóa phát hành. Khóa và mật khẩu chỉ đặt trong GitHub Repository secrets, không commit vào kho mã nguồn.

Khi có bản mới:

1. Tăng `versionCode` và `versionName` trong `app/build.gradle.kts`.
2. Build APK release bằng GitHub Actions với bốn secret `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` và `RELEASE_KEY_PASSWORD`.
3. Đưa APK lên địa chỉ tải công khai (Cloudflare R2 phù hợp với APK lớn hơn 25 MiB).
4. Tính SHA-256 của APK.
5. Sửa `release-site/version.json` với phiên bản, đường dẫn APK và SHA-256, rồi triển khai tệp này lên `boi-ungdung1.pages.dev`.

Ứng dụng chỉ hiện nút cập nhật khi `versionCode` trên Cloudflare lớn hơn bản đang cài. Android vẫn yêu cầu người dùng xác nhận cài đặt; ứng dụng không tự cài âm thầm.
