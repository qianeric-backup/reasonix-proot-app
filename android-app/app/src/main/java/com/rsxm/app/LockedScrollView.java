package com.rsxm.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** 禁滚 ScrollView：功能页整体固定不可滑动，仅内部列表/输入框二级滚动。
 *  不拦截子 View 触摸（onInterceptTouchEvent 返回 false），自身不消费滚动（onTouchEvent 返回 false），
 *  因此内部嵌套的 ScrollView/EditText 仍可正常滚动。 */
public class LockedScrollView extends ScrollView {
    public LockedScrollView(Context c) { super(c); }
    public LockedScrollView(Context c, AttributeSet a) { super(c, a); }
    public LockedScrollView(Context c, AttributeSet a, int d) { super(c, a, d); }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) { return false; }

    @Override
    public boolean onTouchEvent(MotionEvent ev) { return false; }
}
