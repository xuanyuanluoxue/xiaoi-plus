package plus.xiaoi.module.core.apptool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 全系统 AppTool provider 的白名单目录。
 *
 * <p>AppTool 是 HyperOS 的「应用能力对 Agent 开放」标准通道：action
 * {@code com.aios.osbot.action.APP_TOOL_PROVIDER}，工具声明在各 APK 的
 * {@code assets/app_tools.json}。设备实测 17 个 provider / 112 个工具
 * （见 {@code reverse/docs/09-ai-components.md}）。</p>
 *
 * <p>这里只收录「我们愿意对外暴露」的组合：{@code read} 直接放行，
 * {@code write} 需要调用方显式确认。外呼、发文件、提交反馈、清理等危险工具
 * 刻意不收录——不在白名单里就是不可调用。</p>
 */
public final class AppToolCatalog {

    /** 风险级：只读。 */
    public static final String READ = "read";
    /** 风险级：会改系统状态，需 confirm。 */
    public static final String WRITE = "write";

    /** 一个可调用项。 */
    public static final class Entry {
        public final String app;
        public final String tool;
        public final String risk;
        public final String params;

        Entry(String app, String tool, String risk, String params) {
            this.app = app;
            this.tool = tool;
            this.risk = risk;
            this.params = params;
        }

        public boolean writable() {
            return WRITE.equals(risk);
        }
    }

    /** provider 别名 → {包名, Service 全类名}。 */
    private static final String[][] PROVIDERS = {
            {"alarm", "com.android.deskclock", "com.android.deskclock.tool.AlarmToolService"},
            {"calendar", "com.android.calendar", "com.miui.calendar.ai.tool.CalendarToolService"},
            {"notes", "com.miui.notes", "com.miui.notes.agent.tool.provider.NoteToolService"},
            {"recorder", "com.android.soundrecorder",
                    "com.android.soundrecorder.SoundRecorderToolService"},
            {"gallery", "com.miui.gallery", "com.aios.apptoolsdk.service.AppToolProviderService"},
            {"weather", "com.miui.weather2", "com.aios.apptoolsdk.service.AppToolProviderService"},
            {"phone", "com.android.phone", "com.android.phone.tools.PhoneToolService"},
            {"aicr", "com.xiaomi.aicr", "com.xiaomi.aicr.apptools.tools.AIEngineToolService"},
            {"launcher", "com.miui.home", "com.miui.home.miclaw.LauncherToolService"},
            {"milink", "com.milink.service", "com.milink.service.osbot.MiLinkToolService"},
            {"cleaner", "com.miui.cleanmaster",
                    "com.miui.optimizecenter.service.CleanerToolService"},
            {"wallet", "com.mipay.wallet",
                    "com.xiaomi.jr.apptool.WalletAppToolProviderService"},
    };

    /** 条目：app / tool / risk / 参数摘要。 */
    private static final List<Entry> ENTRIES = Arrays.asList(
            // ---------------------------------------------------------- 闹钟
            read("alarm", "deskclock_query_alarms", "-"),
            read("alarm", "deskclock_query_timer", "-"),
            write("alarm", "deskclock_add_alarm", "hour*, minutes*, label, daysOfWeek, vibrate, enabled"),
            write("alarm", "deskclock_update_alarm", "id*, hour, minutes, label, daysOfWeek, vibrate, enabled"),
            write("alarm", "deskclock_delete_alarm", "id*"),
            write("alarm", "deskclock_start_timer", "hours, minutes, seconds"),
            write("alarm", "deskclock_pause_timer", "-"),
            write("alarm", "deskclock_resume_timer", "-"),
            write("alarm", "deskclock_cancel_timer", "-"),

            // ---------------------------------------------------------- 日历
            read("calendar", "query_events", "start_time*, end_time*, keyword, max_results"),
            read("calendar", "query_festivals", "year, month, name"),
            read("calendar", "query_legal_holidays", "year, month, name"),
            write("calendar", "create_event",
                    "title*, start_time*, end_time*, is_all_day, location, description, rrule, reminder"),
            write("calendar", "create_birthday", "name*, date*, date_type, use_year, reminder"),
            write("calendar", "create_anniversary", "content*, date*, date_type, remind_before_3_day, reminder_time"),
            write("calendar", "create_countdown", "content*, date*, repeat_type, remind_before_3_day, reminder_time"),
            write("calendar", "update_event", "event_id*, title, start_time, end_time, ..."),
            write("calendar", "delete_event", "event_id*"),

            // ---------------------------------------------------------- 录音机
            read("recorder", "search_recordings", "keyword, record_type, offset, limit"),
            read("recorder", "get_transcription", "db_id | file_name"),
            write("recorder", "manage_recording", "action*（start/stop/pause/resume/get_status）, record_format"),
            write("recorder", "play_audio_file", "db_id | file_path"),

            // ---------------------------------------------------------- 天气
            read("weather", "weather_get_city_weather", "city*, belongings"),
            read("weather", "weather_get_city_alert", "city*, belongings"),

            // ---------------------------------------------------------- 笔记
            read("notes", "note_list", "-"),
            read("notes", "note_search", "query*, limit"),
            read("notes", "note_read", "noteId | note_id"),
            read("notes", "note_search_by_time", "startTime, endTime, limit"),
            read("notes", "note_search_by_type", "noteType, limit"),
            read("notes", "note_search_by_tag", "tag*, limit"),
            read("notes", "note_children_list", "parentId | note_id"),
            read("notes", "folder_list", "-"),
            read("notes", "folder_notes", "folderId | folder_id"),
            read("notes", "folder_uncategorized", "-"),
            read("notes", "podcast_search", "keyword, startTime, endTime, limit"),
            read("notes", "time_now", "-"),
            write("notes", "note_create", "title*, content*, imageUri"),
            write("notes", "note_write", "noteId, content*"),
            write("notes", "note_append", "noteId, append_content*"),
            write("notes", "note_update", "noteId*, title, content"),
            write("notes", "card_create", "parentId, title, content, noteType"),
            read("notes", "card_template", "noteType | cardType"),
            write("notes", "folder_create", "name*"),
            write("notes", "note_move_to_folder", "noteId, folderId"),
            write("notes", "note_batch_move_to_folder", "noteIds, folderId"),
            write("notes", "note_pin", "noteId, isPinned"),
            write("notes", "note_insert_image", "noteId, imageSource"),
            write("notes", "note_sync_schedule", "noteId, cancel"),
            write("notes", "rollback_operation", "action*, note_id, snapshot_id"),

            // ---------------------------------------------------------- 相册
            read("gallery", "gallery_search", "query*, time_ranges, media_type, ai_category, limit"),
            read("gallery", "gallery_resolve_path", "paths*"),
            read("gallery", "gallery_get_photo_info", "media_ids*, fields"),
            read("gallery", "gallery_sort", "media_ids*, sort_by, order, min_aesthetic_score"),
            read("gallery", "gallery_list_collection", "-"),
            read("gallery", "gallery_assess", "media*, type（1=美学 2=质量）"),
            write("gallery", "gallery_mark", "media_ids*, mark_type*, operation*"),
            write("gallery", "gallery_add_to_collection", "media_ids*, album_id*, operation*"),
            write("gallery", "gallery_create_collection", "album_name*"),
            write("gallery", "gallery_manage_story", "type*, title, description, media_list, cover_list"),
            read("gallery", "gallery_assess_trash", "media_ids*"),

            // ---------------------------------------------------------- 电话（只读）
            read("phone", "phone_get_caller_info", "-"),
            write("phone", "phone_settings", "action（get/set）, category（call/record…）"),
            write("phone", "phone_network_settings", "action（get/set）"),

            // ---------------------------------------------------------- 互联 / 其他
            read("aicr", "get_support_event_detail", "-"),
            write("launcher", "home_settings", "action*, 具体设置项"),
            write("milink", "milink_cast_settings", "action*, target"),
            write("milink", "milink_connectivity_settings", "action*, target"),
            write("milink", "milink_manage_screen_media", "action*, direction, device"),
            write("milink", "milink_manage_device_control", "action*, device_type, device_name"),
            write("milink", "milink_manage_tv_control", "action*"),
            write("milink", "milink_desktop_device_switch", "action*"),
            write("milink", "milink_desktop_position", "action*"),
            write("milink", "milink_unlock_switch", "action*"),
            write("cleaner", "manage_cleaner", "action*（查询/扫描/清理）"),
            write("wallet", "get_bookkeeping_categories", "-"),
            write("wallet", "record_bill", "amount*, type*, category…")
    );

    private AppToolCatalog() {}

    private static Entry read(String app, String tool, String params) {
        return new Entry(app, tool, READ, params);
    }

    private static Entry write(String app, String tool, String params) {
        return new Entry(app, tool, WRITE, params);
    }

    /** 全部白名单条目（只读 + 写）。 */
    public static List<Entry> entries() {
        return new ArrayList<Entry>(ENTRIES);
    }

    /** 只读条目。 */
    public static List<Entry> readable() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : ENTRIES) {
            if (!e.writable()) out.add(e);
        }
        return out;
    }

    /** 按别名精确查；不存在返回 null（不认识 = 不许调）。 */
    public static Entry find(String app, String tool) {
        if (app == null || tool == null) return null;
        for (Entry e : ENTRIES) {
            if (e.app.equals(app) && e.tool.equals(tool)) return e;
        }
        return null;
    }

    /** 别名是否存在（用于区分「app 写错」和「tool 没授权」）。 */
    public static boolean hasApp(String app) {
        for (String[] p : PROVIDERS) {
            if (p[0].equals(app)) return true;
        }
        return false;
    }

    /** 别名对应的包名；未知返回 null。 */
    public static String packageOf(String app) {
        for (String[] p : PROVIDERS) {
            if (p[0].equals(app)) return p[1];
        }
        return null;
    }

    /** 别名对应的 Service 全类名；未知返回 null。 */
    public static String serviceOf(String app) {
        for (String[] p : PROVIDERS) {
            if (p[0].equals(app)) return p[2];
        }
        return null;
    }

    /** 全部别名（稳定顺序）。 */
    public static List<String> apps() {
        List<String> out = new ArrayList<String>();
        for (String[] p : PROVIDERS) out.add(p[0]);
        return out;
    }

    /** 别名下已授权的工具；未知别名返回空表。 */
    public static List<Entry> byApp(String app) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : ENTRIES) {
            if (e.app.equals(app)) out.add(e);
        }
        return out;
    }
}
