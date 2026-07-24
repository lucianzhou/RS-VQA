package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_setting")
public class UserSettingEntity extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(nullable = false, length = 20)
    private String locale;

    @Column(name = "reduced_motion", nullable = false)
    private boolean reducedMotion;

    @Column(name = "external_image_opt_in", nullable = false)
    private boolean externalImageOptIn;

    @Column(name = "settings_json", columnDefinition = "TEXT")
    private String settingsJson;

    protected UserSettingEntity() {
    }

    public UserSettingEntity(UserEntity user) {
        this.user = user;
        this.locale = "zh-CN";
        this.settingsJson = "{}";
    }

    public String getLocale() {
        return locale;
    }

    public boolean isReducedMotion() {
        return reducedMotion;
    }

    public boolean isExternalImageOptIn() {
        return externalImageOptIn;
    }

    public String getSettingsJson() {
        return settingsJson;
    }

    public void update(String locale, Boolean reducedMotion, Boolean externalImageOptIn) {
        if (locale != null) this.locale = locale;
        if (reducedMotion != null) this.reducedMotion = reducedMotion;
        if (externalImageOptIn != null) this.externalImageOptIn = externalImageOptIn;
    }
}
