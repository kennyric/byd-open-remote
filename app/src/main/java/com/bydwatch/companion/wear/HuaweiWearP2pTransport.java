package com.bydwatch.companion.wear;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.huawei.wearengine.HiWear;
import com.huawei.wearengine.WearEngineException;
import com.huawei.wearengine.auth.AuthClient;
import com.huawei.wearengine.auth.Permission;
import com.huawei.wearengine.common.WearEngineErrorCode;
import com.huawei.wearengine.device.Device;
import com.huawei.wearengine.device.DeviceClient;
import com.huawei.wearengine.p2p.Message;
import com.huawei.wearengine.p2p.P2pClient;
import com.huawei.wearengine.p2p.Receiver;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production implementation of PeerTransport backed by the Huawei Wear Engine SDK.
 * Degrades cleanly and gracefully when Huawei Health is absent or no watch is paired.
 */
public class HuaweiWearP2pTransport implements PeerTransport {

    private static final String TAG = "HuaweiWearP2pTransport";

    private final Context context;
    private final WearEngineConfig config;

    private AuthClient authClient;
    private DeviceClient deviceClient;
    private P2pClient p2pClient;

    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicBoolean permissionGranted = new AtomicBoolean(false);
    private final AtomicBoolean wantsListening = new AtomicBoolean(false);
    private final AtomicBoolean listening = new AtomicBoolean(false);

    private Device targetDevice;
    private Receiver registeredReceiver;
    private MessageListener messageListener;
    private TransportStateListener stateListener;

    public HuaweiWearP2pTransport(Context context, WearEngineConfig config) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.config = config != null ? config : new WearEngineConfig();
        initClients();
    }

    private void initClients() {
        if (context == null) {
            available.set(false);
            return;
        }

        try {
            authClient = HiWear.getAuthClient(context);
            deviceClient = HiWear.getDeviceClient(context);
            p2pClient = HiWear.getP2pClient(context);

            if (p2pClient != null) {
                if (config.getWatchPackageName() != null && !config.getWatchPackageName().isEmpty()) {
                    p2pClient.setPeerPkgName(config.getWatchPackageName());
                }
                if (config.hasFingerprint()) {
                    p2pClient.setPeerFingerPrint(config.getWatchCertFingerprint());
                } else {
                    Log.w(TAG, "Watch cert fingerprint is empty. P2P communication is on standby until AppGallery signing.");
                }
            }

            if (authClient != null) {
                authClient.checkPermission(Permission.DEVICE_MANAGER)
                        .addOnSuccessListener(granted -> {
                            boolean isGranted = (granted != null && granted);
                            permissionGranted.set(isGranted);
                            Log.d(TAG, "Wear Engine DEVICE_MANAGER permission granted: " + isGranted);
                            if (stateListener != null) {
                                stateListener.onPermissionChanged(isGranted);
                            }
                            if (isGranted && wantsListening.get() && !listening.get()) {
                                resolveDeviceAndRegister(messageListener);
                            }
                        })
                        .addOnFailureListener(e -> {
                            handleException("checkPermission", e);
                            if (stateListener != null) {
                                stateListener.onPermissionChanged(false);
                            }
                        });
            }
        } catch (Throwable t) {
            handleException("initClients", t);
        }
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public boolean hasPermission() {
        return permissionGranted.get();
    }

    @Override
    public boolean isListening() {
        return listening.get();
    }

    @Override
    public void setTransportStateListener(TransportStateListener listener) {
        this.stateListener = listener;
        if (listener != null) {
            if (!available.get()) {
                listener.onAvailabilityChanged(false, "Wear Engine unavailable");
            } else {
                listener.onPermissionChanged(permissionGranted.get());
                listener.onReceiverStateChanged(listening.get(), listening.get() ? "Listening" : "Idle");
            }
        }
    }

    @Override
    public void requestPermission(Activity activity, PeerTransport.AuthCallback callback) {
        try {
            AuthClient client = activity != null ? HiWear.getAuthClient(activity) : authClient;
            if (client == null) {
                if (callback != null) callback.onError("AuthClient unavailable");
                return;
            }

            client.requestPermission(new com.huawei.wearengine.auth.AuthCallback() {
                @Override
                public void onOk(Permission[] permissions) {
                    permissionGranted.set(true);
                    Log.i(TAG, "User granted DEVICE_MANAGER permission in Huawei Health");
                    if (stateListener != null) {
                        stateListener.onPermissionChanged(true);
                    }
                    if (wantsListening.get() && !listening.get()) {
                        resolveDeviceAndRegister(messageListener);
                    }
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }

                @Override
                public void onCancel() {
                    permissionGranted.set(false);
                    Log.w(TAG, "User cancelled or denied DEVICE_MANAGER permission in Huawei Health");
                    if (stateListener != null) {
                        stateListener.onPermissionChanged(false);
                    }
                    if (callback != null) {
                        callback.onError("Authorization cancelled or denied");
                    }
                }
            }, Permission.DEVICE_MANAGER).addOnFailureListener(e -> {
                handleException("requestPermission", e);
                if (stateListener != null) {
                    stateListener.onPermissionChanged(false);
                }
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            });
        } catch (Throwable t) {
            handleException("requestPermission", t);
            if (callback != null) {
                callback.onError(t.getMessage());
            }
        }
    }

    @Override
    public synchronized void startListening(MessageListener listener) {
        this.messageListener = listener;
        this.wantsListening.set(true);

        if (!permissionGranted.get()) {
            Log.w(TAG, "Cannot start listening immediately: DEVICE_MANAGER permission not yet granted (will auto-register once granted)");
            if (stateListener != null) {
                stateListener.onReceiverStateChanged(false, "Waiting for DEVICE_MANAGER permission");
            }
            return;
        }

        resolveDeviceAndRegister(listener);
    }

    private synchronized void resolveDeviceAndRegister(MessageListener listener) {
        if (deviceClient == null || p2pClient == null) {
            Log.w(TAG, "Cannot start listening: Wear Engine clients not initialized");
            if (stateListener != null) {
                stateListener.onReceiverStateChanged(false, "Wear Engine clients unavailable");
            }
            return;
        }

        try {
            deviceClient.getBondedDevices()
                    .addOnSuccessListener(devices -> {
                        Device chosen = selectPreferredDevice(devices);
                        if (chosen == null) {
                            Log.w(TAG, "No bonded Huawei wearable devices found in Huawei Health");
                            listening.set(false);
                            if (stateListener != null) {
                                stateListener.onReceiverStateChanged(false, "No paired wearable device found");
                            }
                            return;
                        }

                        targetDevice = chosen;
                        Log.i(TAG, "Selected wearable device: " + chosen.getName() + " (" + chosen.getModel() + ", connected=" + chosen.isConnected() + ")");

                        registerReceiverInternal(chosen, listener);
                    })
                    .addOnFailureListener(e -> {
                        listening.set(false);
                        handleException("getBondedDevices", e);
                        if (stateListener != null) {
                            stateListener.onReceiverStateChanged(false, "Failed to get bonded devices: " + e.getMessage());
                        }
                    });
        } catch (Throwable t) {
            listening.set(false);
            handleException("getBondedDevices", t);
            if (stateListener != null) {
                stateListener.onReceiverStateChanged(false, "Error: " + t.getMessage());
            }
        }
    }

    private synchronized Device selectPreferredDevice(List<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        for (Device d : devices) {
            if (d.isConnected()) {
                return d;
            }
        }
        return devices.get(0);
    }

    private synchronized void registerReceiverInternal(Device device, MessageListener listener) {
        try {
            if (p2pClient == null || device == null) return;

            unregisterReceiverInternal();

            registeredReceiver = message -> {
                if (message != null && message.getData() != null) {
                    if (listener != null) {
                        listener.onMessageReceived(message.getData());
                    }
                }
            };

            p2pClient.registerReceiver(device, registeredReceiver)
                    .addOnSuccessListener(v -> {
                        listening.set(true);
                        Log.i(TAG, "Registered Wear Engine P2P receiver for device: " + device.getName());
                        if (stateListener != null) {
                            stateListener.onReceiverStateChanged(true, "Listening on " + device.getName());
                        }
                    })
                    .addOnFailureListener(e -> {
                        listening.set(false);
                        handleException("registerReceiver", e);
                        if (stateListener != null) {
                            stateListener.onReceiverStateChanged(false, "registerReceiver failed: " + e.getMessage());
                        }
                    });
        } catch (Throwable t) {
            listening.set(false);
            handleException("registerReceiver", t);
            if (stateListener != null) {
                stateListener.onReceiverStateChanged(false, "Error registering receiver: " + t.getMessage());
            }
        }
    }

    @Override
    public synchronized void stopListening() {
        wantsListening.set(false);
        listening.set(false);
        messageListener = null;
        unregisterReceiverInternal();
        if (stateListener != null) {
            stateListener.onReceiverStateChanged(false, "Stopped");
        }
    }

    private synchronized void unregisterReceiverInternal() {
        if (p2pClient != null && registeredReceiver != null) {
            try {
                p2pClient.unregisterReceiver(registeredReceiver)
                        .addOnSuccessListener(v -> Log.d(TAG, "Unregistered Wear Engine P2P receiver"))
                        .addOnFailureListener(e -> Log.w(TAG, "Failed to unregister receiver: " + e.getMessage()));
            } catch (Throwable t) {
                Log.w(TAG, "Error unregistering receiver: " + t.getMessage());
            } finally {
                registeredReceiver = null;
            }
        }
    }

    @Override
    public void send(byte[] payload, PeerTransport.SendCallback callback) {
        if (payload == null) {
            if (callback != null) callback.onFailure(-1, "Payload cannot be null");
            return;
        }

        if (targetDevice == null) {
            Log.w(TAG, "Target watch device not resolved yet, attempting to query bonded devices before send...");
            if (deviceClient != null) {
                try {
                    deviceClient.getBondedDevices().addOnSuccessListener(devices -> {
                        Device chosen = selectPreferredDevice(devices);
                        if (chosen != null) {
                            targetDevice = chosen;
                            sendInternal(chosen, payload, callback);
                        } else {
                            if (callback != null) {
                                callback.onFailure(WearEngineErrorCode.ERROR_CODE_UNBOUND_DEVICE, "No bonded Huawei wearable device available");
                            }
                        }
                    }).addOnFailureListener(e -> {
                        if (callback != null) {
                            callback.onFailure(-1, "Failed to resolve device: " + e.getMessage());
                        }
                    });
                    return;
                } catch (Throwable t) {
                    if (callback != null) callback.onFailure(-1, t.getMessage());
                    return;
                }
            } else {
                if (callback != null) callback.onFailure(-1, "Wear Engine deviceClient unavailable");
                return;
            }
        }

        sendInternal(targetDevice, payload, callback);
    }

    private void sendInternal(Device device, byte[] payload, PeerTransport.SendCallback callback) {
        if (p2pClient == null) {
            if (callback != null) callback.onFailure(-1, "Wear Engine P2pClient is unavailable");
            return;
        }
        if (device == null) {
            if (callback != null) callback.onFailure(WearEngineErrorCode.ERROR_CODE_UNBOUND_DEVICE, "Target device is null");
            return;
        }

        try {
            Message msg = new Message.Builder()
                    .setPayload(payload)
                    .build();

            p2pClient.send(device, msg, new com.huawei.wearengine.p2p.SendCallback() {
                @Override
                public void onSendResult(int resultCode) {
                    if (resultCode == WearEngineErrorCode.ERROR_CODE_SUCCESS || resultCode == WearEngineErrorCode.ERROR_CODE_COMM_SUCCESS) {
                        Log.d(TAG, "Message sent successfully to " + device.getName());
                        if (callback != null) callback.onSuccess();
                    } else {
                        String errMsg = WearEngineErrorCode.getErrorMsgFromCode(resultCode);
                        Log.w(TAG, "Message send failed [" + resultCode + "]: " + errMsg);
                        if (callback != null) callback.onFailure(resultCode, errMsg);
                    }
                }

                @Override
                public void onSendProgress(long progress) {
                }
            }).addOnFailureListener(e -> {
                Log.w(TAG, "P2pClient.send task failed: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure(-1, e.getMessage());
                }
            });
        } catch (Throwable t) {
            handleException("sendInternal", t);
            if (callback != null) {
                callback.onFailure(-1, t.getMessage());
            }
        }
    }

    private void handleException(String method, Throwable t) {
        if (t instanceof WearEngineException) {
            WearEngineException wee = (WearEngineException) t;
            int code = wee.getErrorCode();
            Log.w(TAG, method + " WearEngineException: " + code + " - " + wee.getMessage());
            if (code == WearEngineErrorCode.ERROR_CODE_UNINSTALL_HEALTH) {
                available.set(false);
                Log.w(TAG, "Huawei Health app is not installed on this phone");
                if (stateListener != null) {
                    stateListener.onAvailabilityChanged(false, "Huawei Health app not installed");
                }
            } else if (stateListener != null) {
                stateListener.onError(code, wee.getMessage());
            }
        } else {
            Log.w(TAG, method + " error: " + t.getMessage(), t);
            if (stateListener != null) {
                stateListener.onError(-1, t.getMessage());
            }
        }
    }

    public Device getTargetDevice() {
        return targetDevice;
    }
}
