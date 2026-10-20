package com.gow3.android.game;

import android.content.Context;

/** 存档对话框桥（SaveDialogBridge）向宿主（GameActivity）索取的窄接口：只暴露对话框真正
 *  需要的回调，不让桥直接依赖宿主内部实现。 */
interface SaveDialogHost {
    Context context();
    PadBridge pad();
    void closeDrawer();
    void immersive();
    void requestFocus();
    int dp(float n);
    /** 宿主是否已进入销毁流程。刻意不叫 isDestroyed()：Activity 自带同名的框架方法
     *  （语义是"最终 onDestroy 已走完"），接口方法不能遮蔽/覆盖它。 */
    boolean isHostDestroyed();
}
