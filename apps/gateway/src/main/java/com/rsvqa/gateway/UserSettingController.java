package com.rsvqa.gateway;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.domain.UserSettingEntity;
import com.rsvqa.gateway.repository.UserRepository;
import com.rsvqa.gateway.repository.UserSettingRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/user/settings")
public class UserSettingController {

    private final UserSettingService settings;

    public UserSettingController(UserSettingService settings) {
        this.settings = settings;
    }

    @GetMapping
    public UserSettingResponse get() {
        return settings.get();
    }

    @PatchMapping
    public UserSettingResponse update(@Valid @RequestBody UpdateUserSettingRequest request) {
        return settings.update(request);
    }

    record UpdateUserSettingRequest(
            @Pattern(regexp = "zh-CN|en-US", message = "当前仅支持 zh-CN 或 en-US。")
            String locale,
            Boolean reducedMotion,
            Boolean externalImageOptIn
    ) {
    }

    record UserSettingResponse(
            UUID id,
            String locale,
            boolean reducedMotion,
            boolean externalImageOptIn,
            String externalImageBoundary
    ) {
    }
}

@Service
class UserSettingService {

    private final UserRepository users;
    private final UserSettingRepository settings;

    UserSettingService(UserRepository users, UserSettingRepository settings) {
        this.users = users;
        this.settings = settings;
    }

    @Transactional
    UserSettingController.UserSettingResponse get() {
        return response(current());
    }

    @Transactional
    UserSettingController.UserSettingResponse update(UserSettingController.UpdateUserSettingRequest request) {
        UserSettingEntity setting = current();
        setting.update(request.locale(), request.reducedMotion(), request.externalImageOptIn());
        return response(setting);
    }

    private UserSettingEntity current() {
        UserEntity user = currentUser();
        return settings.findByUserId(user.getId())
                .orElseGet(() -> settings.save(new UserSettingEntity(user)));
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private static UserSettingController.UserSettingResponse response(UserSettingEntity setting) {
        return new UserSettingController.UserSettingResponse(
                setting.getId(),
                setting.getLocale(),
                setting.isReducedMotion(),
                setting.isExternalImageOptIn(),
                setting.isExternalImageOptIn()
                        ? "用户已允许在明确选择外部视觉模型时发送图像；每次调用仍保留来源与审计。"
                        : "默认禁止向外部 Provider 发送上传图像。"
        );
    }
}
