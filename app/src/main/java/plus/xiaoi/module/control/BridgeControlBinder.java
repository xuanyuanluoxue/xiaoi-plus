package plus.xiaoi.module.control;

import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import plus.xiaoi.module.Logs;
import plus.xiaoi.module.core.ControlKeys;

/**
 * 控制面的 Binder 方法面：只暴露 pull/register/report/logs 四个事务。
 *
 * 安全边界：每个 transaction 的第一条语句取 {@link Binder#getCallingUid()}，
 * 只有 UID 名下有 com.miui.voiceassist 才继续；其余一律 SecurityException。
 * 这里不做任何文件/Provider 暴露，避免 confused deputy。
 */
final class BridgeControlBinder extends Binder {

    private final BridgeControlService service;

    BridgeControlBinder(BridgeControlService service) {
        this.service = service;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        final int uid = Binder.getCallingUid();
        if (!service.checkUid(uid)) {
            service.recordDenied(uid);
            throw new SecurityException("control plane: uid " + uid + " denied");
        }
        data.enforceInterface(ControlKeys.DESCRIPTOR);

        switch (code) {
            case ControlKeys.C2S_PULL_CONFIG: {
                Bundle req = data.readBundle(getClass().getClassLoader());
                int protocol = req == null ? 0 : req.getInt(ControlKeys.KEY_PROTOCOL, 0);
                Bundle out = new Bundle();
                out.putInt(ControlKeys.KEY_PROTOCOL, ControlKeys.PROTOCOL_VERSION);
                if (protocol > ControlKeys.PROTOCOL_VERSION) {
                    out.putBoolean(ControlKeys.KEY_OK, false);
                    out.putString(ControlKeys.KEY_ERROR, "unsupported protocol");
                } else {
                    BridgeControlService.LoadResult r = service.loadConfigBlocking();
                    if (r.error != null) {
                        out.putBoolean(ControlKeys.KEY_OK, false);
                        out.putString(ControlKeys.KEY_ERROR, r.error);
                    } else {
                        out.putBoolean(ControlKeys.KEY_OK, true);
                        out.putString(ControlKeys.KEY_CONFIG_JSON, r.json);
                        out.putString(ControlKeys.KEY_CONFIG_HASH, r.hash);
                    }
                }
                reply.writeNoException();
                reply.writeBundle(out);
                return true;
            }
            case ControlKeys.C2S_REGISTER: {
                Bundle in = data.readBundle(getClass().getClassLoader());
                service.register(in);
                reply.writeNoException();
                return true;
            }
            case ControlKeys.C2S_REPORT_STATE: {
                Bundle in = data.readBundle(getClass().getClassLoader());
                service.reportState(in);
                reply.writeNoException();
                return true;
            }
            case ControlKeys.C2S_APPEND_LOGS: {
                Bundle in = data.readBundle(getClass().getClassLoader());
                service.appendLogs(in == null ? null : in.getString(ControlKeys.KEY_LOGS, ""));
                reply.writeNoException();
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }
}
