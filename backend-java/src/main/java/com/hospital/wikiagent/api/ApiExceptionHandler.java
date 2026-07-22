package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.agent.model.AgentModelUnavailableException;
import com.hospital.wikiagent.agent.model.PlannerOutputException;
import com.hospital.wikiagent.details.IndicatorDetailException;

/**
 * 实现 {@code ApiExceptionHandler} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HospitalAuthException.class)
    public ResponseEntity<Map<String, String>> auth(HospitalAuthException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.status().value() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(Map.of("detail", exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> validation(Exception exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", "请求参数不符合接口约束"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("detail", exception.getMessage()));
    }

    @ExceptionHandler(PlannerOutputException.class)
    public ResponseEntity<Map<String, String>> planner(PlannerOutputException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    @ExceptionHandler(AgentModelUnavailableException.class)
    public ResponseEntity<Map<String, String>> model(AgentModelUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    @ExceptionHandler(IndicatorDetailException.class)
    public ResponseEntity<Map<String, Object>> detail(IndicatorDetailException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "detail", Map.of(
                        "code", exception.code(),
                        "message", exception.getMessage())));
    }
}
