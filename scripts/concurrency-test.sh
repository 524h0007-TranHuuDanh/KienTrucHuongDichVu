#!/usr/bin/env bash
#
# concurrency-test.sh — Kiểm thử E2E hai tình huống tranh chấp của đề bài
# (tiêu chí 6.2 và 6.3 trong rubric giữa kỳ), chạy thật qua API Gateway :8080.
#
# YÊU CẦU: hệ thống đã chạy `docker compose up -d` và gateway đã sẵn sàng.
#
# Kịch bản A (tiêu chí 6.2 — nhiều giao dịch đồng thời trên CÙNG MỘT tài khoản):
#   524h0456 (số dư 15.000.000) đồng thời thanh toán 2 MSSV khác nhau
#   (524H0001 = 8.500.000 và 524H0002 = 9.200.000, tổng 17.700.000 > số dư).
#   Bất biến phải giữ: KHÔNG BAO GIỜ chi vượt số dư, số dư cuối không âm,
#   và số tiền bị trừ đúng bằng tổng các giao dịch SUCCESS.
#
# Kịch bản B (tiêu chí 6.3 — nhiều người cùng đóng MỘT khoản học phí):
#   524h0088 và 524h0456 cùng đóng 524H0005 (6.500.000).
#   Hệ thống có HAI lớp chặn và tuỳ thời điểm mà lớp nào bắt được:
#     - Lớp 1 (guard P-17 trong initiatePayment): người thứ hai bị chặn ngay
#       khi khởi tạo vì khoản học phí đang có giao dịch OTP còn hiệu lực.
#     - Lớp 2 (saga bù trừ): nếu cả hai cùng lọt qua initiate, người thua sẽ
#       thua ở bước mark-paid và ĐƯỢC HOÀN TIỀN đầy đủ.
#   Script chấp nhận cả hai đường và kiểm bất biến chung: đúng 1 SUCCESS,
#   học phí chỉ được gạch một lần, và số dư người thua được bảo toàn.
#
# Exit code: 0 nếu cả hai kịch bản PASS, 1 nếu có kịch bản FAIL.

set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Lấy REDIS_PASSWORD từ .env (redis chạy với --requirepass)
REDIS_PASSWORD=""
if [ -f "$ROOT_DIR/.env" ]; then
    REDIS_PASSWORD="$(grep -E '^REDIS_PASSWORD=' "$ROOT_DIR/.env" | head -1 | cut -d= -f2-)"
fi

PASS_A=0
PASS_B=0
NOTE_A=""
NOTE_B=""

# ─────────────────────────── tiện ích ───────────────────────────

c_red()   { printf '\033[31m%s\033[0m' "$1"; }
c_green() { printf '\033[32m%s\033[0m' "$1"; }
c_dim()   { printf '\033[2m%s\033[0m' "$1"; }

info() { printf '  %s\n' "$(c_dim "$1")"; }
fail_reason() { printf '  %s %s\n' "$(c_red '✗')" "$1"; }
ok_reason()   { printf '  %s %s\n' "$(c_green '✓')" "$1"; }

psql_db() { docker exec postgres psql -U postgres -d "$1" -tAc "$2" 2>/dev/null; }
redis_cli() { docker exec ibanking-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@" 2>/dev/null; }

json_str()  { grep -o "\"$2\":\"[^\"]*\"" "$1" 2>/dev/null | head -1 | cut -d'"' -f4; }
json_num()  { grep -o "\"$2\":[0-9.]*"    "$1" 2>/dev/null | head -1 | cut -d: -f2; }

# So sánh số thập phân bằng awk (bash không làm được số thực)
feq() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a==b)}'; }
fge() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a>=b)}'; }

login() { # $1=username $2=password -> in ra "token|userId"
    local out="$TMP/login_$1.json"
    curl -s --max-time 20 -X POST "$GATEWAY/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}" -o "$out"
    printf '%s|%s' "$(json_str "$out" accessToken)" "$(json_str "$out" userId)"
}

balance_of() { psql_db authdb "SELECT balance FROM users WHERE username='$1'"; }

get_otp() { # $1=transactionId — giá trị lưu bằng GenericJackson2JsonRedisSerializer nên có dấu nháy
    redis_cli GET "otp:$1" | tr -d '"' | tr -d '\r'
}

# ─────────────────────── reset dữ liệu fixture ───────────────────────
# Không phải "sửa code cho dễ test": đây là dựng lại trạng thái đầu để script
# chạy lại được nhiều lần. data.sql dùng ON CONFLICT DO NOTHING nên khởi động
# lại container KHÔNG khôi phục các dòng đã bị gạch nợ.
reset_fixtures() {
    psql_db authdb "UPDATE users SET balance = 100000000.00 WHERE username='524h0088';
                    UPDATE users SET balance =  15000000.00 WHERE username='524h0456';
                    DELETE FROM balance_entries;" >/dev/null

    # 524H0002: cố ý để HK2-2425 ĐÃ ĐÓNG, để GET /api/tuition/524H0002 trả kỳ
    # HK1-2526 (9.200.000) — cần số tiền này thì tổng 2 giao dịch của kịch bản A
    # mới vượt số dư 15.000.000.
    psql_db tuitiondb "
        UPDATE tuitions SET paid=false, paid_at=NULL, transaction_id=NULL
            WHERE id IN ('22222222-2222-2222-2222-222222222001',
                         '22222222-2222-2222-2222-222222222003',
                         '22222222-2222-2222-2222-222222222005',
                         '22222222-2222-2222-2222-222222222007');
        UPDATE tuitions SET paid=true, paid_at='2025-03-01 09:00:00',
                            transaction_id='33333333-3333-3333-3333-333333333002'
            WHERE id='22222222-2222-2222-2222-222222222006';
        UPDATE tuitions SET paid=true, paid_at='2025-03-15 09:00:00',
                            transaction_id='33333333-3333-3333-3333-333333333003'
            WHERE id='22222222-2222-2222-2222-222222222002';
        UPDATE tuitions SET paid=true, paid_at='2025-10-01 10:00:00',
                            transaction_id='33333333-3333-3333-3333-333333333001'
            WHERE id='22222222-2222-2222-2222-222222222004';" >/dev/null

    psql_db paymentdb "DELETE FROM transactions;" >/dev/null

    # Xoá OTP + bộ đếm rate-limit (3 OTP/giờ/user) để chạy lại không bị 429
    local keys
    keys="$(redis_cli --scan --pattern 'otp:*' | tr -d '\r')"
    if [ -n "$keys" ]; then
        while IFS= read -r k; do [ -n "$k" ] && redis_cli DEL "$k" >/dev/null; done <<< "$keys"
    fi
}

# ─────────────────────────── preflight ───────────────────────────

preflight() {
    printf '\n%s\n' "── Kiểm tra môi trường ──"
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$GATEWAY/v3/api-docs/swagger-config" 2>/dev/null)"
    if [ "$code" != "200" ]; then
        fail_reason "Gateway $GATEWAY không phản hồi (HTTP ${code:-timeout}). Chạy 'docker compose up -d' trước."
        exit 1
    fi
    ok_reason "Gateway sẵn sàng tại $GATEWAY"

    for c in postgres ibanking-redis; do
        if ! docker ps --format '{{.Names}}' | grep -qx "$c"; then
            fail_reason "Container '$c' chưa chạy."
            exit 1
        fi
    done
    ok_reason "Container postgres + redis đang chạy"

    if [ -z "$(redis_cli PING | tr -d '\r')" ]; then
        fail_reason "Không kết nối được redis (kiểm tra REDIS_PASSWORD trong .env)."
        exit 1
    fi
    ok_reason "Redis phản hồi PING"
}

# ══════════════════════════ KỊCH BẢN A ══════════════════════════

scenario_a() {
    printf '\n%s\n' "── Kịch bản A: 1 tài khoản, 2 giao dịch song song, tổng tiền > số dư ──"
    reset_fixtures

    local tok uid
    IFS='|' read -r tok uid <<< "$(login 524h0456 123456)"
    if [ -z "$tok" ]; then fail_reason "Không đăng nhập được 524h0456"; NOTE_A="login lỗi"; return; fi

    local before; before="$(balance_of 524h0456)"
    info "Số dư trước: $before  (524H0001 = 8.500.000 + 524H0002 = 9.200.000 = 17.700.000)"

    # Khởi tạo SONG SONG hai giao dịch trên 2 MSSV khác nhau
    curl -s --max-time 30 -X POST "$GATEWAY/api/payments/initiate" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d '{"mssv":"524H0001"}' -o "$TMP/a_init1.json" &
    local p1=$!
    curl -s --max-time 30 -X POST "$GATEWAY/api/payments/initiate" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d '{"mssv":"524H0002"}' -o "$TMP/a_init2.json" &
    local p2=$!
    wait $p1 $p2

    local tx1 tx2
    tx1="$(json_str "$TMP/a_init1.json" transactionId)"
    tx2="$(json_str "$TMP/a_init2.json" transactionId)"
    if [ -z "$tx1" ] || [ -z "$tx2" ]; then
        fail_reason "Không khởi tạo được cả 2 giao dịch"
        info "tx1=${tx1:-<rỗng>} tx2=${tx2:-<rỗng>}"
        info "resp1: $(head -c 200 "$TMP/a_init1.json")"
        info "resp2: $(head -c 200 "$TMP/a_init2.json")"
        NOTE_A="initiate thất bại"
        return
    fi
    info "Đã tạo 2 giao dịch: ${tx1:0:8}… và ${tx2:0:8}…"

    local otp1 otp2
    otp1="$(get_otp "$tx1")"; otp2="$(get_otp "$tx2")"
    if [ -z "$otp1" ] || [ -z "$otp2" ]; then
        fail_reason "Không lấy được OTP từ Redis"; NOTE_A="thiếu OTP"; return
    fi

    # Xác thực OTP SONG SONG — đây mới là chỗ tranh chấp số dư thật sự
    curl -s --max-time 60 -X POST "$GATEWAY/api/payments/verify-otp" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d "{\"transactionId\":\"$tx1\",\"otp\":\"$otp1\"}" -o "$TMP/a_ver1.json" &
    local v1=$!
    curl -s --max-time 60 -X POST "$GATEWAY/api/payments/verify-otp" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d "{\"transactionId\":\"$tx2\",\"otp\":\"$otp2\"}" -o "$TMP/a_ver2.json" &
    local v2=$!
    wait $v1 $v2

    local n_success debited after
    n_success="$(psql_db paymentdb "SELECT COUNT(*) FROM transactions WHERE status='SUCCESS'")"
    debited="$(psql_db paymentdb "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE status='SUCCESS'")"
    after="$(balance_of 524h0456)"

    info "Kết quả: SUCCESS=$n_success, tổng đã trừ=$debited, số dư sau=$after"

    local ok=1
    if ! fge "$after" 0; then fail_reason "Số dư ÂM ($after) — đã chi vượt số dư!"; ok=0
    else ok_reason "Số dư không âm ($after)"; fi

    if [ "$n_success" != "1" ]; then
        fail_reason "Kỳ vọng đúng 1 giao dịch SUCCESS, thực tế $n_success"
        ok=0
    else
        ok_reason "Đúng 1 giao dịch SUCCESS (giao dịch còn lại bị chặn vì thiếu số dư)"
    fi

    local expected; expected="$(awk -v b="$before" -v d="$debited" 'BEGIN{printf "%.2f", b-d}')"
    if feq "$after" "$expected"; then
        ok_reason "Sổ sách khớp: $before − $debited = $after"
    else
        fail_reason "Sổ sách LỆCH: kỳ vọng $expected, thực tế $after"; ok=0
    fi

    PASS_A=$ok
    NOTE_A="SUCCESS=$n_success, số dư $before → $after"
}

# ══════════════════════════ KỊCH BẢN B ══════════════════════════

scenario_b() {
    printf '\n%s\n' "── Kịch bản B: 2 tài khoản cùng đóng MỘT khoản học phí (524H0005) ──"
    reset_fixtures

    local tokA uidA tokB uidB
    IFS='|' read -r tokA uidA <<< "$(login 524h0088 123456)"
    IFS='|' read -r tokB uidB <<< "$(login 524h0456 123456)"
    if [ -z "$tokA" ] || [ -z "$tokB" ]; then fail_reason "Không đăng nhập được 2 user"; NOTE_B="login lỗi"; return; fi

    local balA_before balB_before
    balA_before="$(balance_of 524h0088)"; balB_before="$(balance_of 524h0456)"
    info "Số dư trước — 524h0088: $balA_before | 524h0456: $balB_before"

    # Hai người cùng khởi tạo trên CÙNG một MSSV, song song
    curl -s --max-time 30 -X POST "$GATEWAY/api/payments/initiate" \
        -H "Authorization: Bearer $tokA" -H 'Content-Type: application/json' \
        -d '{"mssv":"524H0005"}' -o "$TMP/b_initA.json" &
    local pa=$!
    curl -s --max-time 30 -X POST "$GATEWAY/api/payments/initiate" \
        -H "Authorization: Bearer $tokB" -H 'Content-Type: application/json' \
        -d '{"mssv":"524H0005"}' -o "$TMP/b_initB.json" &
    local pb=$!
    wait $pa $pb

    local txA txB
    txA="$(json_str "$TMP/b_initA.json" transactionId)"
    txB="$(json_str "$TMP/b_initB.json" transactionId)"

    local path
    if [ -n "$txA" ] && [ -n "$txB" ]; then
        path="ca-hai-lot-initiate"
        info "Cả hai cùng lọt qua initiate → tranh chấp sẽ được giải ở bước mark-paid (saga bù trừ)"
    else
        path="guard-P17-chan"
        info "Một người bị chặn ngay ở initiate (guard P-17)"
        local blocked; blocked="$(json_str "$TMP/b_initA.json" message)"
        [ -n "$txA" ] && blocked="$(json_str "$TMP/b_initB.json" message)"
        info "Thông báo cho người bị chặn: ${blocked:-<không có>}"
    fi

    # Ai có OTP thì xác thực — chạy song song nếu cả hai đều có
    local vpids=()
    if [ -n "$txA" ]; then
        local otpA; otpA="$(get_otp "$txA")"
        curl -s --max-time 60 -X POST "$GATEWAY/api/payments/verify-otp" \
            -H "Authorization: Bearer $tokA" -H 'Content-Type: application/json' \
            -d "{\"transactionId\":\"$txA\",\"otp\":\"$otpA\"}" -o "$TMP/b_verA.json" &
        vpids+=($!)
    fi
    if [ -n "$txB" ]; then
        local otpB; otpB="$(get_otp "$txB")"
        curl -s --max-time 60 -X POST "$GATEWAY/api/payments/verify-otp" \
            -H "Authorization: Bearer $tokB" -H 'Content-Type: application/json' \
            -d "{\"transactionId\":\"$txB\",\"otp\":\"$otpB\"}" -o "$TMP/b_verB.json" &
        vpids+=($!)
    fi
    [ ${#vpids[@]} -gt 0 ] && wait "${vpids[@]}"

    local n_success paid_cnt winner_tx balA_after balB_after
    n_success="$(psql_db paymentdb "SELECT COUNT(*) FROM transactions WHERE status='SUCCESS'")"
    paid_cnt="$(psql_db tuitiondb "SELECT COUNT(*) FROM tuitions WHERE id='22222222-2222-2222-2222-222222222007' AND paid=true")"
    winner_tx="$(psql_db tuitiondb "SELECT COALESCE(transaction_id::text,'') FROM tuitions WHERE id='22222222-2222-2222-2222-222222222007'")"
    balA_after="$(balance_of 524h0088)"; balB_after="$(balance_of 524h0456)"

    info "Kết quả: SUCCESS=$n_success, học phí paid=$paid_cnt, tx gạch nợ=${winner_tx:0:8}…"
    info "Số dư sau — 524h0088: $balA_after | 524h0456: $balB_after"

    local ok=1
    if [ "$n_success" != "1" ]; then
        fail_reason "Kỳ vọng đúng 1 giao dịch SUCCESS, thực tế $n_success"; ok=0
    else
        ok_reason "Đúng 1 giao dịch SUCCESS — chỉ một người thanh toán được"
    fi

    if [ "$paid_cnt" != "1" ]; then
        fail_reason "Khoản học phí 524H0005 chưa được gạch nợ đúng một lần"; ok=0
    else
        ok_reason "Học phí được gạch nợ đúng 1 lần, bởi giao dịch thắng"
    fi

    # Bất biến quan trọng nhất: người thua không mất tiền.
    # Tổng tiền bị trừ của CẢ HAI người phải đúng bằng đúng 1 lần số tiền học phí.
    local total_before total_after spent
    total_before="$(awk -v a="$balA_before" -v b="$balB_before" 'BEGIN{printf "%.2f", a+b}')"
    total_after="$(awk -v a="$balA_after" -v b="$balB_after" 'BEGIN{printf "%.2f", a+b}')"
    spent="$(awk -v a="$total_before" -v b="$total_after" 'BEGIN{printf "%.2f", a-b}')"

    if feq "$spent" "6500000.00"; then
        ok_reason "Tổng tiền bị trừ = 6500000.00 = đúng MỘT lần học phí (người thua được bảo toàn/hoàn đủ)"
    else
        fail_reason "Tổng tiền bị trừ = $spent, kỳ vọng 6500000.00 (người thua bị mất tiền hoặc hoàn thiếu)"; ok=0
    fi

    # Tuỳ đường đi mà bằng chứng cần kiểm khác nhau.
    if [ "$path" = "ca-hai-lot-initiate" ]; then
        # Cả hai cùng cầm OTP -> người thua PHẢI thua ở mark-paid và PHẢI được
        # hoàn tiền. Kiểm tận bút toán: phải có ĐỦ cặp DEBIT + CREDIT bằng nhau.
        local loser_tx loser_msg dbt crd
        loser_tx="$(psql_db paymentdb "SELECT id FROM transactions WHERE status='FAILED' LIMIT 1")"
        loser_msg="$(psql_db paymentdb "SELECT COALESCE(error_message,'') FROM transactions WHERE id='$loser_tx'")"
        dbt="$(psql_db authdb "SELECT COALESCE(SUM(amount),0) FROM balance_entries WHERE transaction_id='$loser_tx' AND type='DEBIT'")"
        crd="$(psql_db authdb "SELECT COALESCE(SUM(amount),0) FROM balance_entries WHERE transaction_id='$loser_tx' AND type='CREDIT'")"

        if printf '%s' "$loser_msg" | grep -q 'Học phí đã được người khác thanh toán'; then
            ok_reason "Giao dịch thua FAILED đúng lý do: \"$loser_msg\""
        else
            fail_reason "Giao dịch thua sai lý do: \"${loser_msg:-<rỗng>}\""; ok=0
        fi

        if [ -n "$dbt" ] && ! feq "$dbt" "0" && feq "$dbt" "$crd"; then
            ok_reason "Saga bù trừ chạy đúng: đã trừ $dbt rồi hoàn lại $crd (khớp tuyệt đối)"
        else
            fail_reason "Hoàn tiền KHÔNG khớp: DEBIT=${dbt:-0} nhưng CREDIT=${crd:-0}"; ok=0
        fi
    else
        # Guard P-17 chặn từ đầu -> người thua không được có bút toán nào.
        local n_entries
        n_entries="$(psql_db authdb "SELECT COUNT(*) FROM balance_entries WHERE type='DEBIT'")"
        if [ "$n_entries" = "1" ]; then
            ok_reason "Người thua bị chặn ngay ở initiate nên chưa từng bị trừ tiền (chỉ 1 bút toán DEBIT)"
        else
            fail_reason "Kỳ vọng đúng 1 bút toán DEBIT, thực tế $n_entries"; ok=0
        fi
    fi

    PASS_B=$ok
    NOTE_B="đường: $path, SUCCESS=$n_success, tổng trừ=$spent"
}

# ═════════════════════════════ main ═════════════════════════════

printf '\n%s\n' "════════════════════════════════════════════════════════════════"
printf '%s\n'   "  KIỂM THỬ ĐỒNG THỜI — iBanking (tiêu chí 6.2 & 6.3)"
printf '%s\n'   "════════════════════════════════════════════════════════════════"

preflight
scenario_a
scenario_b

printf '\n%s\n' "════════════════════════════════════════════════════════════════"
printf '%s\n'   "  KẾT QUẢ"
printf '%s\n'   "════════════════════════════════════════════════════════════════"
printf '  %-58s %s\n' "Kịch bản" "Kết quả"
printf '  %s\n' "------------------------------------------------------------------"
if [ "$PASS_A" = "1" ]; then
    printf '  %-58s %s\n' "A. Không chi vượt số dư (1 tài khoản, 2 GD song song)" "$(c_green PASS)"
else
    printf '  %-58s %s\n' "A. Không chi vượt số dư (1 tài khoản, 2 GD song song)" "$(c_red FAIL)"
fi
printf '     %s\n' "$(c_dim "${NOTE_A:-}")"
if [ "$PASS_B" = "1" ]; then
    printf '  %-58s %s\n' "B. Một học phí chỉ thanh toán được một lần (2 tài khoản)" "$(c_green PASS)"
else
    printf '  %-58s %s\n' "B. Một học phí chỉ thanh toán được một lần (2 tài khoản)" "$(c_red FAIL)"
fi
printf '     %s\n' "$(c_dim "${NOTE_B:-}")"
printf '\n'

if [ "$PASS_A" = "1" ] && [ "$PASS_B" = "1" ]; then
    printf '  %s\n\n' "$(c_green 'TẤT CẢ KỊCH BẢN PASS')"
    exit 0
fi
printf '  %s\n\n' "$(c_red 'CÓ KỊCH BẢN FAIL')"
exit 1
