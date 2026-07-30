package app.revanced.extension.edge.tabs;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class TabSwitcherMobile {
    private static final int GRID_SPAN_COUNT = 2;
    private static final int MINIMUM_BOTTOM_CLEARANCE_DP = 12;
    private static final long REMOVAL_ANIMATION_DURATION_MS = 200;
    private static final long MOVE_ANIMATION_DURATION_MS = 250;
    private static final PathInterpolator MOVE_INTERPOLATOR =
        new PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f);
    private static final WeakHashMap<ViewGroup, LayoutState> INSTALLED_VIEWS =
        new WeakHashMap<>();

    private TabSwitcherMobile() {
    }

    public static void install(Object tabList) {
        if (!(tabList instanceof ViewGroup view)) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post(() -> install(view));
            return;
        }
        if (INSTALLED_VIEWS.containsKey(view)) {
            return;
        }

        LayoutState state = new LayoutState(view);
        INSTALLED_VIEWS.put(view, state);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    public static void updateLayout(Object tabList, int itemCount) {
        if (!(tabList instanceof ViewGroup view)) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            view.post(() -> updateLayout(view, itemCount));
            return;
        }

        install(view);
        LayoutState state = INSTALLED_VIEWS.get(view);
        if (state != null) {
            state.updateLayout(itemCount);
        }
    }

    private static final class LayoutState {
        private final WeakReference<ViewGroup> viewReference;
        private final int minimumBottomClearance;
        private float targetTranslationY = Float.NaN;
        private Animator translationAnimator;

        private LayoutState(ViewGroup view) {
            viewReference = new WeakReference<>(view);
            minimumBottomClearance = Math.round(
                view.getResources().getDisplayMetrics().density *
                    MINIMUM_BOTTOM_CLEARANCE_DP
            );
        }

        private void updateLayout(int itemCount) {
            ViewGroup view = viewReference.get();
            if (view == null) {
                return;
            }

            int tallestChild = 0;
            for (int index = 0; index < view.getChildCount(); index++) {
                View child = view.getChildAt(index);
                child.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                if (child.getHeight() == 0) {
                    continue;
                }

                tallestChild = Math.max(tallestChild, child.getHeight());
            }
            if (tallestChild == 0 || itemCount <= 0) {
                return;
            }

            float desiredTranslationY = 0.0f;
            if (itemCount <= GRID_SPAN_COUNT) {
                int emptySpace = Math.max(
                    0,
                    view.getHeight() -
                        view.getPaddingTop() -
                        view.getPaddingBottom() -
                        tallestChild
                );
                desiredTranslationY = -Math.max(
                    minimumBottomClearance,
                    emptySpace / 2
                );
            }
            if (
                Float.compare(desiredTranslationY, targetTranslationY) == 0 &&
                    (
                        (translationAnimator != null &&
                            translationAnimator.isStarted()) ||
                            Math.abs(
                                view.getTranslationY() - desiredTranslationY
                            ) < 0.5f
                    )
            ) {
                return;
            }

            boolean animate = !Float.isNaN(targetTranslationY) && view.isShown();
            boolean movingUp = desiredTranslationY < view.getTranslationY();
            targetTranslationY = desiredTranslationY;
            if (translationAnimator != null) {
                translationAnimator.cancel();
                translationAnimator = null;
            }
            if (!animate) {
                view.setTranslationY(desiredTranslationY);
                return;
            }

            ObjectAnimator animator = ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_Y,
                desiredTranslationY
            );
            animator.setDuration(MOVE_ANIMATION_DURATION_MS);
            animator.setStartDelay(
                movingUp ? REMOVAL_ANIMATION_DURATION_MS : 0
            );
            animator.setInterpolator(MOVE_INTERPOLATOR);
            translationAnimator = animator;
            animator.start();
        }
    }
}
