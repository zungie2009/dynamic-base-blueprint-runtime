package org.blueprintruntime.uiconfig;

import java.util.List;

/**
 * One layer of UI presentation/interaction settings for a table, exactly as declared
 * at one step of {@code uiConfigurationContract.composition.resolutionOrder}: the
 * built-in safe fallback, the file's {@code defaults}, or one {@code perTable}
 * override. Every field is nullable here — {@code null} means "not set at this
 * layer, inherit from the layer beneath" — until {@link #overrideWith(UiSurfaceRule)}
 * has merged every applicable layer, at which point every field is guaranteed
 * non-null (the built-in fallback covers all of them).
 */
public record UiSurfaceRule(
        String rowMode,
        List<String> rowActions,
        String onSaveSuccess,
        String createPlacement,
        String createOnSuccess,
        String standaloneViewEditRoutes,
        String blockedDeletePresentation,
        String blockedDeleteMessage,
        String successPresentation
) {
    /**
     * Merges {@code override} on top of {@code this}: any field {@code override}
     * actually sets wins outright, including a list-valued field such as {@code
     * rowActions} — arrays replace inherited arrays rather than concatenate, per
     * {@code composition.arrayRule}. Passing {@code null} returns {@code this}
     * unchanged (no override layer present).
     */
    public UiSurfaceRule overrideWith(UiSurfaceRule override) {
        if (override == null) return this;
        return new UiSurfaceRule(
                override.rowMode != null ? override.rowMode : rowMode,
                override.rowActions != null ? override.rowActions : rowActions,
                override.onSaveSuccess != null ? override.onSaveSuccess : onSaveSuccess,
                override.createPlacement != null ? override.createPlacement : createPlacement,
                override.createOnSuccess != null ? override.createOnSuccess : createOnSuccess,
                override.standaloneViewEditRoutes != null ? override.standaloneViewEditRoutes : standaloneViewEditRoutes,
                override.blockedDeletePresentation != null ? override.blockedDeletePresentation : blockedDeletePresentation,
                override.blockedDeleteMessage != null ? override.blockedDeleteMessage : blockedDeleteMessage,
                override.successPresentation != null ? override.successPresentation : successPresentation
        );
    }

    public boolean isInlineEditable() {
        return "INLINE_EDITABLE".equals(rowMode);
    }

    public boolean actionEnabled(String action) {
        return rowActions != null && rowActions.contains(action);
    }

    public boolean createInline() {
        return "INLINE_BELOW_LIST".equals(createPlacement);
    }

    public boolean staysOnListAfterSave() {
        return "STAY_ON_LIST".equals(onSaveSuccess);
    }

    public boolean staysOnListAfterCreate() {
        return "STAY_ON_LIST".equals(createOnSuccess);
    }

    public boolean standaloneRoutesRemoved() {
        return "REMOVE".equals(standaloneViewEditRoutes);
    }

    public boolean blockedDeleteIsInlineBanner() {
        return "INLINE_BANNER".equals(blockedDeletePresentation);
    }

    public boolean successBannerEnabled() {
        return "INLINE_BANNER".equals(successPresentation);
    }
}
