# Password Reset System Analysis

## Current Implementation

### Email Configuration
- **Library**: `com.draines/postal "2.0.5"` (Clojure wrapper for Jakarta Mail)
- **Config Location**: `resources/dev.edn` at `:web/email`
- **Current Dev Config**: All fields set to `nil` (disabled)
- **Production**: No `prod.edn` exists; Docker only overrides MongoDB

```clojure
:web/email {:host nil
            :user nil
            :pass nil
            :ssl  nil}
```

### Code Flow
1. **Request**: `POST /forgot` → `forgot-password-handler` (`src/clj/web/auth.clj:219`)
2. **Token Generation**: 40-char hex from 20 secure random bytes (`src/clj/web/auth.clj:207-217`)
3. **Token Storage**: Added directly to user document in `users` collection
4. **Token Expiry**: 1 hour from generation (`inst/plus (inst/now) 1 chrono/hours`)
5. **Reset Page**: `GET /reset/:token` → `reset-password-page` (`src/clj/web/pages.clj:57`)
6. **Reset Submit**: `POST /reset/:token` → `reset-password-handler` (`src/clj/web/auth.clj:240`)

### Database Schema
Reset tokens stored in user documents:
```clojure
{:username "johndoe"
 :email "john@example.com"
 :password "$2a$10$encrypted..."
 :resetPasswordToken "a1b2c3d4e5f67890abcdef1234567890abcdef12"  ; Added on reset request
 :resetPasswordExpires #inst "2024-01-01T01:00:00.000-00:00"       ; Added on reset request
 ;; ... other user fields}
```

### Current Indexes
- Regular index on `:resetPasswordToken` (`src/clj/tasks/index.clj:28`)

## Configuration Issues for Private Servers

### Hardcoded Values
1. **Sender Email**: Hardcoded to `"support@jinteki.net"` (`src/clj/web/auth.clj:228,257`)
2. **Domain URLs**: Dynamic via `Host` header (good)
3. **Email Subjects**: Reference "Jinteki" branding

### SMTP2GO Compatibility
✅ **Fully supported** via standard SMTP configuration:
```clojure
:web/email {:host "mail.smtp2go.com"
            :user "your-smtp2go-username"
            :pass "your-smtp2go-password"
            :tls  true
            :port 2525}
```

## Security Vulnerabilities

### ⚠️ CRITICAL: No Rate Limiting
- **No protection** on `/forgot` endpoint beyond CSRF
- Vulnerable to email bombing attacks
- No IP or email-based rate limiting
- No attempt tracking or logging

### Token Management Issues
- Expired tokens persist in database forever (TODO: check if they are deleted on reset)
- Each new reset overwrites previous token (good)
- 1-hour expiry is reasonable

## Improvement Checklist

### High Priority (Security)
- [ ] **Add IP-based rate limiting** (3 attempts per 5 minutes per IP)
- [ ] **Add email-based rate limiting** (1 attempt per 15 minutes per email)
- [ ] **Add attempt logging** for monitoring and abuse detection
- [ ] **Make sender email configurable** (vs hardcoded `support@jinteki.net`)

### Medium Priority (Operational)
- [ ] **Add daily IP limits** (e.g., 20 resets per IP per day)
- [ ] **Enhance monitoring** with abuse pattern alerts
- [ ] **Update email templates** for custom domains/branding

### Low Priority (Nice-to-Have)
- [ ] **Add CAPTCHA** for high-frequency IPs
- [ ] **Implement honeypot fields** for bot detection
- [ ] **Shorter token expiry** (15 minutes vs 1 hour)

## Rate Limiting Implementation Guide

### Existing Patterns
- Chat rate limiting exists (`src/clj/web/chat.clj:57-64`) - use as template
- IP extraction logic exists (`src/clj/web/auth.clj:110-112`)
- Throttler library available (`throttler "1.0.1"`)

### Configuration Example
```clojure
:web/email {:host "mail.smtp2go.com"
            :user "smtp-username"
            :pass "smtp-password"
            :tls true
            :port 2525
            :from "noreply@yourdomain.com"          ; Add configurable sender
            :reset-rate-window 300                  ; 5 minutes
            :reset-rate-limit 3                     ; 3 attempts per IP per window
            :reset-email-window 900                 ; 15 minutes
            :reset-email-limit 1}                   ; 1 attempt per email per window
```

### Implementation Files
- **Rate limiting logic**: Add to `forgot-password-handler` (`src/clj/web/auth.clj:219`)

## Testing Notes
- Dev environment has email disabled (all `nil` values)
- No test coverage for password reset flow currently
- Manual testing required for email delivery validation
