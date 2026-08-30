-- Seed data cho tuition-service (plan.md Mục 4).
-- Chạy MỖI LẦN khởi động (spring.sql.init.mode=always) -> mọi câu lệnh phải idempotent.
-- UUID ghi cứng (literal) để test lặp lại được.
-- Insert students TRƯỚC tuitions vì tuitions.mssv là FK -> students.mssv.

INSERT INTO students (id, mssv, full_name, email, faculty, created_at) VALUES
    ('11111111-1111-1111-1111-111111111001', '524H0088', 'Tran Huu Danh',   '524h0088@tdtu.edu.vn', 'Cong Nghe Thong Tin', '2025-01-01 08:00:00'),
    ('11111111-1111-1111-1111-111111111002', '524H0100', 'Nguyen Thanh Quy','524h0100@tdtu.edu.vn', 'Cong Nghe Thong Tin', '2025-01-01 08:00:00'),
    ('11111111-1111-1111-1111-111111111003', '524H0123', 'Le Minh Anh',    '524h0123@tdtu.edu.vn', 'Kinh Te',             '2025-01-01 08:00:00'),
    ('11111111-1111-1111-1111-111111111004', '524H0456', 'Pham Thi Mai',   '524h0456@tdtu.edu.vn', 'Ke Toan',             '2025-01-01 08:00:00'),
    ('11111111-1111-1111-1111-111111111005', '524H0789', 'Vo Quoc Bao',    '524h0789@tdtu.edu.vn', 'Cong Nghe Thong Tin', '2025-01-01 08:00:00')
ON CONFLICT (id) DO NOTHING;

-- 524H0088: luong chinh, chua dong.
INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, transaction_id, version, created_at) VALUES
    ('22222222-2222-2222-2222-222222222001', '524H0088', 'HK1-2526', '2025-10-15', 8500000.00, false, NULL, NULL, 0, '2025-09-01 08:00:00')
ON CONFLICT (id) DO NOTHING;

-- 524H0100: no ky cu (HK2-2425) + ky moi (HK1-2526) -> GET phai tra ky cu.
INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, transaction_id, version, created_at) VALUES
    ('22222222-2222-2222-2222-222222222002', '524H0100', 'HK2-2425', '2025-03-15', 5000000.00, false, NULL, NULL, 0, '2025-02-01 08:00:00'),
    ('22222222-2222-2222-2222-222222222003', '524H0100', 'HK1-2526', '2025-10-15', 9200000.00, false, NULL, NULL, 0, '2025-09-01 08:00:00')
ON CONFLICT (id) DO NOTHING;

-- 524H0123: da dong het -> test 404 "khong con khoan chua dong".
INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, transaction_id, version, created_at) VALUES
    ('22222222-2222-2222-2222-222222222004', '524H0123', 'HK1-2526', '2025-10-15', 7800000.00, true, '2025-10-01 10:00:00', '33333333-3333-3333-3333-333333333001', 0, '2025-09-01 08:00:00')
ON CONFLICT (id) DO NOTHING;

-- 524H0456: so tien lon hon so du user demo (15.000.000) -> test 409 khong du so du o debit.
INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, transaction_id, version, created_at) VALUES
    ('22222222-2222-2222-2222-222222222005', '524H0456', 'HK1-2526', '2025-10-15', 20000000.00, false, NULL, NULL, 0, '2025-09-01 08:00:00')
ON CONFLICT (id) DO NOTHING;

-- 524H0789: da dong ky cu (HK2-2425), con no ky moi (HK1-2526) -> GET phai tra ky moi.
INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, transaction_id, version, created_at) VALUES
    ('22222222-2222-2222-2222-222222222006', '524H0789', 'HK2-2425', '2025-03-15', 6500000.00, true, '2025-03-01 09:00:00', '33333333-3333-3333-3333-333333333002', 0, '2025-02-01 08:00:00'),
    ('22222222-2222-2222-2222-222222222007', '524H0789', 'HK1-2526', '2025-10-15', 6500000.00, false, NULL, NULL, 0, '2025-09-01 08:00:00')
ON CONFLICT (id) DO NOTHING;
