/**
 * Huawei Wear Engine Lite Wearable P2P Client Wrapper
 *
 * Based on Huawei's official Lite Wearable Wear Engine JS SDK specification:
 *   - Device: Huawei Watch GT 4 (HarmonyOS Lite Wearable / LiteOS JS)
 *   - Communication: Bluetooth P2P messaging with paired Android companion app
 *   - Native Binding: '@system.wear' / 'wear' system module
 *
 * This module provides the standard P2pClient and Message.Builder interfaces.
 * If running on a real watch or DevEco Lite Wearable simulator with '@system.wear'
 * available, it delegates directly to native Wear Engine bindings.
 * If running in a test, browser, or Node.js environment without native bindings,
 * it acts gracefully without throwing uncaught exceptions.
 *
 * Ready for drop-in replacement if DevEco imports Huawei's official SDK .js bundle.
 */

/* global wear, system */

// Attempt to resolve native Lite Wearable @system.wear binding if available
let nativeWear = null;
try {
  if (typeof wear !== 'undefined') {
    nativeWear = wear;
  } else if (typeof globalThis !== 'undefined' && globalThis.wear) {
    nativeWear = globalThis.wear;
  } else if (typeof require !== 'undefined') {
    try {
      // In DevEco Lite Wearable runtime, system modules can be required via @system.wear
      nativeWear = require('@system.wear');
    } catch (_e) {
      nativeWear = null;
    }
  }
} catch (_ignored) {
  nativeWear = null;
}

/**
 * Message object transferred between Watch and Phone.
 */
export class Message {
  constructor(builder) {
    this.data = builder ? builder.data : null;
    this.description = builder ? builder.description : '';
    this.type = builder ? builder.type : 0;
  }

  static get Builder() {
    return class {
      constructor() {
        this.data = null;
        this.description = '';
        this.type = 0;
      }

      setData(data) {
        this.data = data;
        return this;
      }

      setDescription(description) {
        this.description = description;
        return this;
      }

      setType(type) {
        this.type = type;
        return this;
      }

      build() {
        return new Message(this);
      }
    };
  }
}

/**
 * P2pClient wrapper for Lite Wearable applications.
 */
export class P2pClient {
  constructor() {
    this.peerPkgName = '';
    this.peerFingerPrint = '';
    this.receiver = null;
    this.isRegistered = false;
  }

  /**
   * Sets the package name of the target Android companion app.
   * @param {string} pkgName - e.g., 'com.bydwatch.companion'
   */
  setPeerPkgName(pkgName) {
    this.peerPkgName = pkgName || '';
    if (nativeWear && typeof nativeWear.setPeerPkgName === 'function') {
      try {
        nativeWear.setPeerPkgName(pkgName);
      } catch (err) {
        console.warn('nativeWear.setPeerPkgName failed:', err);
      }
    }
  }

  /**
   * Sets the SHA-256 certificate fingerprint of the target Android app.
   * Note: DevEco / Wear Engine requires colons removed (e.g. 64 hex chars).
   * @param {string} fingerPrint - SHA-256 fingerprint string
   */
  setPeerFingerPrint(fingerPrint) {
    this.peerFingerPrint = fingerPrint ? fingerPrint.replace(/:/g, '') : '';
    if (nativeWear && typeof nativeWear.setPeerFingerPrint === 'function') {
      try {
        nativeWear.setPeerFingerPrint(this.peerFingerPrint);
      } catch (err) {
        console.warn('nativeWear.setPeerFingerPrint failed:', err);
      }
    }
  }

  /**
   * Registers a receiver callback to receive messages from the paired phone.
   * @param {object} receiver - Object with onSuccess, onFailure, and onReceiveMessage callbacks
   */
  registerReceiver(receiver) {
    this.receiver = receiver;
    this.isRegistered = true;

    if (nativeWear && typeof nativeWear.registerReceiver === 'function') {
      try {
        nativeWear.registerReceiver({
          pkgName: this.peerPkgName,
          fingerPrint: this.peerFingerPrint,
          onSuccess: (data) => {
            if (receiver && typeof receiver.onSuccess === 'function') {
              receiver.onSuccess(data);
            }
          },
          onFailure: (errCode, errDesc) => {
            if (receiver && typeof receiver.onFailure === 'function') {
              receiver.onFailure(errCode, errDesc);
            }
          },
          onReceiveMessage: (msg) => {
            if (receiver && typeof receiver.onReceiveMessage === 'function') {
              receiver.onReceiveMessage(msg);
            }
          }
        });
        return;
      } catch (err) {
        console.warn('nativeWear.registerReceiver failed:', err);
      }
    }

    if (receiver && typeof receiver.onSuccess === 'function') {
      receiver.onSuccess({ code: 0, msg: 'Receiver registered (simulation)' });
    }
  }

  /**
   * Unregisters the currently active receiver.
   * @param {object} [receiver] - Optional specific receiver reference
   */
  unregisterReceiver(receiver) {
    this.receiver = null;
    this.isRegistered = false;

    if (nativeWear && typeof nativeWear.unregisterReceiver === 'function') {
      try {
        nativeWear.unregisterReceiver(receiver);
        return;
      } catch (err) {
        console.warn('nativeWear.unregisterReceiver failed:', err);
      }
    }
  }

  /**
   * Sends a message to the paired Android companion app.
   * @param {Message|string} message - Message instance or string payload
   * @param {object} [callback] - Callback object with onSuccess, onFailure, and onSendResult
   */
  send(message, callback) {
    const payload = (message instanceof Message) ? message.data : message;

    if (nativeWear && typeof nativeWear.send === 'function') {
      try {
        nativeWear.send({
          pkgName: this.peerPkgName,
          fingerPrint: this.peerFingerPrint,
          data: payload,
          onSuccess: (data) => {
            if (callback && typeof callback.onSuccess === 'function') {
              callback.onSuccess(data);
            }
          },
          onFailure: (errCode, errDesc) => {
            if (callback && typeof callback.onFailure === 'function') {
              callback.onFailure(errCode, errDesc);
            }
          },
          onSendResult: (resultCode) => {
            if (callback && typeof callback.onSendResult === 'function') {
              callback.onSendResult(resultCode);
            }
          }
        });
        return;
      } catch (err) {
        console.warn('nativeWear.send failed:', err);
      }
    }

    // Fallback if running outside native watch runtime
    console.info('[WearEngine Sim] send payload:', payload);
    if (callback && typeof callback.onSuccess === 'function') {
      callback.onSuccess({ code: 0, msg: 'Message sent (simulation)' });
    }
  }

  /**
   * Pings the paired phone to test connection status.
   * @param {object} [callback] - Callback object with onSuccess and onFailure
   */
  ping(callback) {
    if (nativeWear && typeof nativeWear.ping === 'function') {
      try {
        nativeWear.ping({
          pkgName: this.peerPkgName,
          fingerPrint: this.peerFingerPrint,
          onSuccess: (data) => {
            if (callback && typeof callback.onSuccess === 'function') {
              callback.onSuccess(data);
            }
          },
          onFailure: (errCode, errDesc) => {
            if (callback && typeof callback.onFailure === 'function') {
              callback.onFailure(errCode, errDesc);
            }
          }
        });
        return;
      } catch (err) {
        console.warn('nativeWear.ping failed:', err);
      }
    }

    if (callback && typeof callback.onSuccess === 'function') {
      callback.onSuccess({ code: 0, msg: 'Ping success (simulation)' });
    }
  }
}

/**
 * Convenience factory to create a P2pClient instance.
 * @returns {P2pClient}
 */
export function getP2pClient() {
  return new P2pClient();
}

export default {
  Message,
  P2pClient,
  getP2pClient
};
