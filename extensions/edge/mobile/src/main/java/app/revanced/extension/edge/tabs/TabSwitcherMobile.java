package app.revanced.extension.edge.tabs;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class TabSwitcherMobile {
    private static final int MINIMUM_BOTTOM_CLEARANCE_DP = 12;
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
        view.setClipToPadding(false);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        view.addOnLayoutChangeListener(state);
        view.post(state);
    }

    public static boolean prepareAnimationTarget(Object tabList) {
        if (!(tabList instanceof ViewGroup view)) {
            return false;
        }

        LayoutState state = INSTALLED_VIEWS.get(view);
        return state != null && state.updateLayout();
    }

    private static final class LayoutState implements Runnable, View.OnLayoutChangeListener {
        private final WeakReference<ViewGroup> viewReference;
        private final int originalStart;
        private final int originalTop;
        private final int originalEnd;
        private final int originalBottom;
        private final int minimumBottomClearance;
        private int appliedBottomClearance;

        private LayoutState(ViewGroup view) {
            viewReference = new WeakReference<>(view);
            originalStart = view.getPaddingStart();
            originalTop = view.getPaddingTop();
            originalEnd = view.getPaddingEnd();
            originalBottom = view.getPaddingBottom();
            minimumBottomClearance = Math.round(
                view.getResources().getDisplayMetrics().density *
                    MINIMUM_BOTTOM_CLEARANCE_DP
            );
        }

        @Override
        public void run() {
            updateLayout();
        }

        private boolean updateLayout() {
            ViewGroup view = viewReference.get();
            if (view == null) {
                return false;
            }

            int contentTop = Integer.MAX_VALUE;
            int contentBottom = Integer.MIN_VALUE;
            int tallestChild = 0;
            for (int index = 0; index < view.getChildCount(); index++) {
                View child = view.getChildAt(index);
                child.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                if (child.getHeight() == 0) {
                    continue;
                }

                contentTop = Math.min(contentTop, child.getTop());
                contentBottom = Math.max(contentBottom, child.getBottom());
                tallestChild = Math.max(tallestChild, child.getHeight());
            }
            if (tallestChild == 0) {
                return false;
            }

            boolean singleRow =
                contentBottom - contentTop <= tallestChild + minimumBottomClearance;
            int desiredBottomClearance = 0;
            if (singleRow) {
                int originalEmptySpace = Math.max(
                    0,
                    contentTop - originalTop + appliedBottomClearance
                );
                desiredBottomClearance = Math.max(
                    minimumBottomClearance,
                    originalEmptySpace / 2
                );
            }
            if (desiredBottomClearance == appliedBottomClearance) {
                return false;
            }

            appliedBottomClearance = desiredBottomClearance;
            view.setPaddingRelative(
                originalStart,
                originalTop,
                originalEnd,
                originalBottom + appliedBottomClearance
            );
            return true;
        }

        @Override
        public void onLayoutChange(
            View view,
            int left,
            int top,
            int right,
            int bottom,
            int oldLeft,
            int oldTop,
            int oldRight,
            int oldBottom
        ) {
            run();
        }
    }
}
