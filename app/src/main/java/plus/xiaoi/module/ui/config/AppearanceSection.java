package plus.xiaoi.module.ui.config;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import plus.xiaoi.module.R;
import plus.xiaoi.module.ThemeManager;

/**
 * 外观板块：三个主题 chip 的绑定与选中态。
 *
 * 主题写入仍走 {@link ThemeManager}；重建页面由宿主传入的 {@link Runnable} 执行，
 * 避免 section 直接依赖 Activity。
 */
public final class AppearanceSection {

    private final Context ctx;
    private final Runnable recreate;
    private final TextView[] themeChips = new TextView[3];

    public AppearanceSection(Context ctx, View root, Runnable recreate) {
        this.ctx = ctx;
        this.recreate = recreate;
        themeChips[0] = (TextView) root.findViewById(R.id.chip_theme_0);
        themeChips[1] = (TextView) root.findViewById(R.id.chip_theme_1);
        themeChips[2] = (TextView) root.findViewById(R.id.chip_theme_2);
    }

    public void bind() {
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            if (themeChips[i] == null) continue;
            themeChips[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ThemeManager.set(ctx, mode);
                    recreate.run();
                }
            });
        }
        render();
    }

    private void render() {
        int cur = ThemeManager.get(ctx);
        for (int i = 0; i < 3; i++) {
            TextView chip = themeChips[i];
            if (chip == null) continue;
            boolean on = i == cur;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_ok : R.drawable.bg_chip);
            chip.setTextColor(ctx.getColor(on ? R.color.primary : R.color.text_secondary));
        }
    }
}
