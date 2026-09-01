package com.tdtu.ibanking.tuition.util;

  import io.jsonwebtoken.Claims;
  import io.jsonwebtoken.Jwts;
  import io.jsonwebtoken.security.Keys;
  import io.jsonwebtoken.ExpiredJwtException;
  import io.jsonwebtoken.security.SignatureException;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.stereotype.Component;

  import java.nio.charset.StandardCharsets;
  import java.security.Key;
  import java.util.UUID;
  import lombok.extern.slf4j.Slf4j;
  import lombok.RequiredArgsConstructor;

  @Slf4j
  @Component
  public class JwtUtil {

      @Value("${jwt.secret}")
      private String secret;

      // 1. Dựng lại đúng chiếc chìa khoá mà auth-service đã dùng để ký
      private Key getSignKey() {
          return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
      }

      // 2. Mở token ra, lấy toàn bộ nội dung bên trong
      //    Nếu chữ ký sai hoặc token hết hạn -> hàm này NÉM LỖI, không trả về null
      public Claims extractAllClaims(String token) {
          return Jwts.parserBuilder().setSigningKey(getSignKey()).build().parseClaimsJws(token).getBody();
      }

      // 3. Lấy riêng userId ra, đổi từ chuỗi sang UUID
      public UUID getUserIdFromToken(String token) {
            String userIdStr = extractAllClaims(token).get("userId", String.class);
            return UUID.fromString(userIdStr);
      }

      // 4. Kiểm token hợp lệ không -> trả true/false thay vì ném lỗi
      public boolean validateToken(String token) {
            try {
                extractAllClaims(token);
                return true;
            } catch (ExpiredJwtException e) {
                log.warn("Token hết hạn");
                return false;
            } catch (SignatureException e) {
                log.warn("Chữ ký token không hợp lệ");
                return false;
            } catch (Exception e) {
                log.warn("Token không hợp lệ: {}", e.getMessage());
                return false;
            }
      }
  }