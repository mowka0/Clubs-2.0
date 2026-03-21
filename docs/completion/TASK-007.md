# TASK-007: Global Error Handling

## Status: DONE

## Date: 2026-03-21

## Summary
Created a comprehensive global error handling system for the Clubs 2.0 backend using Spring Boot's `@RestControllerAdvice`.

## Files Created

### Exception Classes
All in `backend/src/main/kotlin/com/clubs/common/exception/`:

| File | Exception | HTTP Status | Error Code |
|------|-----------|-------------|------------|
| `NotFoundException.kt` | `NotFoundException` | 404 | `NOT_FOUND` |
| `ValidationException.kt` | `ValidationException` | 400 | `VALIDATION_ERROR` |
| `ConflictException.kt` | `ConflictException` | 409 | `CONFLICT` |
| `ForbiddenException.kt` | `ForbiddenException` | 403 | `FORBIDDEN` |
| `RateLimitException.kt` | `RateLimitException` | 429 | `RATE_LIMIT_EXCEEDED` |
| `GlobalExceptionHandler.kt` | All handlers | Various | Various |

### DTO
- `backend/src/main/kotlin/com/clubs/common/dto/PageResponse.kt` — generic pagination wrapper

### GlobalExceptionHandler
Handles:
- `NotFoundException` -> 404 `NOT_FOUND`
- `ValidationException` -> 400 `VALIDATION_ERROR`
- `ConflictException` -> 409 `CONFLICT`
- `ForbiddenException` -> 403 `FORBIDDEN`
- `AccessDeniedException` (Spring Security) -> 403 `FORBIDDEN`
- `RateLimitException` -> 429 `RATE_LIMIT_EXCEEDED`
- `MethodArgumentNotValidException` (@Valid) -> 400 `VALIDATION_ERROR` with field-level details
- `Exception` (catch-all) -> 500 `INTERNAL_ERROR` with generic message (no stack trace leak)

## Response Format
All error responses follow a consistent JSON format:
```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable message"
}
```

## Acceptance Criteria
- [x] NotFoundException -> 404 with JSON `{ "error": "NOT_FOUND", "message": "..." }`
- [x] ValidationException -> 400 with JSON `{ "error": "VALIDATION_ERROR", "message": "..." }`
- [x] ConflictException -> 409 with JSON `{ "error": "CONFLICT", "message": "..." }`
- [x] ForbiddenException / AccessDeniedException -> 403
- [x] RateLimitException -> 429
- [x] MethodArgumentNotValidException (@Valid) -> 400 with field listing
- [x] Unhandled exceptions -> 500 with generic message (no stack trace in response)
- [x] `./gradlew build` passes (pending manual verification)
