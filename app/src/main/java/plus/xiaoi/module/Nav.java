package plus.xiaoi.module;

import android.app.Activity;
import android.content.Intent;

/** 四个页面之间的经典导航。 */
public final class Nav {
    private Nav() {}

    public static void go(Activity a, String tab) {
        Class<?> target;
        if ("config".equals(tab)) target = ConfigActivity.class;
        else if ("logs".equals(tab)) target = LogsActivity.class;
        else if ("about".equals(tab)) target = AboutActivity.class;
        else target = HomeActivity.class;
        if (a.getClass() == target) return;
        Intent i = new Intent(a, target);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
    }
}
