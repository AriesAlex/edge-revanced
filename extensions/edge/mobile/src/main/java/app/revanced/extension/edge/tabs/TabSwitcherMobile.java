package app.revanced.extension.edge.tabs;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class TabSwitcherMobile {
    private static final int BOTTOM_CLEARANCE_DP = 12;
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

    private static final class LayoutState implements Runnable, View.OnLayoutChangeListener {
        private final WeakReference<ViewGroup> viewReference;
        private final int originalStart;
        private final int originalTop;
        private final int originalEnd;
        private final int originalBottom;
        private final int bottomClearance;

        private LayoutState(ViewGroup view) {
            viewReference = new WeakReference<>(view);
            originalStart = view.getPaddingStart();
            originalTop = view.getPaddingTop();
            originalEnd = view.getPaddingEnd();
            originalBottom = view.getPaddingBottom();
            bottomClearance = Math.round(
                view.getResources().getDisplayMetrics().density * BOTTOM_CLEARANCE_DP
            );
        }

        @Override
        public void run() {
            ViewGroup view = viewReference.get();
            if (view == null) {
                return;
            }

            view.setPaddingRelative(
                originalStart,
                originalTop,
                originalEnd,
                originalBottom + bottomClearance
            );
            for (int index = 0; index < view.getChildCount(); index++) {
                view.getChildAt(index).setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            }
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
