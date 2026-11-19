/**
 * Huawei Wear Engine Lite Wearable P2P Client Wrapper
 *
 * Based on Huawei's official Lite Wearable Wear Engine JS SDK specification:
 *   - Device: Huawei Watch GT 4 (HarmonyOS Lite Wearable / JerryScript ES5)
 *   - Communication: Bluetooth P2P messaging with paired Android companion app
 *   - Native Binding: '@system.wear' / 'wear' system module
 *
 * Refactored for JerryScript compatibility:
 *   - Uses ES5 function constructors and prototypes (no ES6 class / static getters)
 *   - Exports { P2pClient, Message, Builder, getP2pClient } at top level
 *   - Extracts payload cleanly across string, data, builder, and description forms
 *   - Tracks wrapper on registerReceiver for clean unregisterReceiver forwarding
 *   - Safe for test / simulation environments without native bindings
 *
 * Ready for drop-in replacement if DevEco imports Huawei's official SDK .js bundle.
 */

/* global wear, system */

var nativeWear = null;
try {
  if (typeof wear !== 'undefined') {
    nativeWear = wear;
  } else if (typeof require !== 'undefined') {
    try {
      nativeWear = require('@system.wear');
    } catch (_e) {
      nativeWear = null;
    }
  }
} catch (_ignored) {
  nativeWear = null;
}

/**
 * Message Builder for Lite Wearable P2P messages.
 * @constructor
 */
export function Builder() {
  this.data = null;
  this.description = '';
  this.payload = null;
  this.type = 0;
}

Builder.prototype.setData = function(data) {
  this.data = data;
  return this;
};

Builder.prototype.setDescription = function(description) {
  this.description = description;
  return this;
};

Builder.prototype.setPayload = function(payload) {
  this.payload = payload;
  return this;
};

Builder.prototype.setType = function(type) {
  this.type = type;
  return this;
};

Builder.prototype.build = function() {
  return new Message(this);
};

/**
 * Message object transferred between Watch and Phone.
 * @constructor
 * @param {Builder} [builder]
 */
export function Message(builder) {
  this.data = builder ? builder.data : null;
  this.description = builder ? builder.description : '';
  this.payload = builder ? builder.payload : null;
  this.type = builder ? builder.type : 0;
  this.builder = builder || null;
}

Message.Builder = Builder;

/**
 * Extracts payload string or buffer from diverse SDK caller conventions.
 * @param {*} message
 * @returns {string|*}
 */
function extractPayload(message) {
  if (typeof message === 'string') {
    return message;
  }
  if (!message || typeof message !== 'object') {
    return '';
  }
  if (message.data !== null && message.data !== undefined) {
    return message.data;
  }
  if (message.builder && typeof message.builder === 'object') {
    if (message.builder.data !== null && message.builder.data !== undefined) {
      return message.builder.data;
    }
    if (message.builder.description) {
      return message.builder.description;
    }
    if (message.builder.payload !== null && message.builder.payload !== undefined) {
      return message.builder.payload;
    }
  }
  if (message.description) {
    return message.description;
  }
  if (message.payload !== null && message.payload !== undefined) {
    return message.payload;
  }
  return '';
}

/**
 * P2pClient wrapper for Lite Wearable applications.
 * @constructor
 */
export function P2pClient() {
  this.peerPkgName = '';
  this.peerFingerPrint = '';
  this.receiver = null;
  this.isRegistered = false;
  this._nativeReceiverWrapper = null;
}

/**
 * Sets the package name of the target Android companion app.
 * @param {string} pkgName - e.g., 'com.bydwatch.companion'
 */
P2pClient.prototype.setPeerPkgName = function(pkgName) {
  this.peerPkgName = pkgName || '';
  if (nativeWear && typeof nativeWear.setPeerPkgName === 'function') {
    try {
      nativeWear.setPeerPkgName(this.peerPkgName);
    } catch (err) {
      console.warn('nativeWear.setPeerPkgName failed:', err);
    }
  }
};

/**
 * Sets the SHA-256 certificate fingerprint of the target Android app.
 * Automatically removes all colons (':') for DevEco / Wear Engine compliance.
 * @param {string} fingerPrint
 */
P2pClient.prototype.setPeerFingerPrint = function(fingerPrint) {
  this.peerFingerPrint = fingerPrint ? String(fingerPrint).split(':').join('') : '';
  if (nativeWear && typeof nativeWear.setPeerFingerPrint === 'function') {
    try {
      nativeWear.setPeerFingerPrint(this.peerFingerPrint);
    } catch (err) {
      console.warn('nativeWear.setPeerFingerPrint failed:', err);
    }
  }
};

/**
 * Registers a receiver callback to receive messages from the paired phone.
 * @param {object} receiver - Object with onSuccess, onFailure, and onReceiveMessage callbacks
 */
P2pClient.prototype.registerReceiver = function(receiver) {
  this.receiver = receiver;
  this.isRegistered = true;

  if (nativeWear && typeof nativeWear.registerReceiver === 'function') {
    var wrapper = {
      pkgName: this.peerPkgName,
      fingerPrint: this.peerFingerPrint,
      onSuccess: function(data) {
        if (receiver && typeof receiver.onSuccess === 'function') {
          receiver.onSuccess(data);
        }
      },
      onFailure: function(errCode, errDesc) {
        if (receiver && typeof receiver.onFailure === 'function') {
          receiver.onFailure(errCode, errDesc);
        }
      },
      onReceiveMessage: function(msg) {
        if (receiver && typeof receiver.onReceiveMessage === 'function') {
          receiver.onReceiveMessage(msg);
        }
      }
    };
    this._nativeReceiverWrapper = wrapper;

    try {
      nativeWear.registerReceiver(wrapper);
    } catch (err) {
      console.warn('nativeWear.registerReceiver failed:', err);
      this.isRegistered = false;
      this._nativeReceiverWrapper = null;
      if (receiver && typeof receiver.onFailure === 'function') {
        receiver.onFailure(-1, err && err.message ? err.message : 'registerReceiver failed');
      }
    }
    return;
  }

  if (receiver && typeof receiver.onSuccess === 'function') {
    receiver.onSuccess({ code: 0, msg: 'Receiver registered (simulation)' });
  }
};

/**
 * Unregisters the currently active receiver.
 * @param {object} [receiver] - Optional specific receiver reference
 */
P2pClient.prototype.unregisterReceiver = function(receiver) {
  var target = receiver || this._nativeReceiverWrapper || this.receiver;
  this.receiver = null;
  this.isRegistered = false;
  this._nativeReceiverWrapper = null;

  if (nativeWear && typeof nativeWear.unregisterReceiver === 'function') {
    try {
      nativeWear.unregisterReceiver(target);
      return;
    } catch (err) {
      console.warn('nativeWear.unregisterReceiver failed:', err);
    }
  }
};

/**
 * Sends a message to the paired Android companion app.
 * @param {Message|Builder|string|object} message
 * @param {object} [callback] - Callback object with onSuccess, onFailure, and onSendResult
 */
P2pClient.prototype.send = function(message, callback) {
  var payload = extractPayload(message);

  if (nativeWear && typeof nativeWear.send === 'function') {
    try {
      nativeWear.send({
        pkgName: this.peerPkgName,
        fingerPrint: this.peerFingerPrint,
        data: payload,
        onSuccess: function(data) {
          if (callback && typeof callback.onSuccess === 'function') {
            callback.onSuccess(data);
          }
        },
        onFailure: function(errCode, errDesc) {
          if (callback && typeof callback.onFailure === 'function') {
            callback.onFailure(errCode, errDesc);
          }
        },
        onSendResult: function(resultCode) {
          if (callback && typeof callback.onSendResult === 'function') {
            callback.onSendResult(resultCode);
          }
        }
      });
    } catch (err) {
      console.warn('nativeWear.send failed:', err);
      if (callback && typeof callback.onFailure === 'function') {
        callback.onFailure(-1, err && err.message ? err.message : 'send failed');
      }
    }
    return;
  }

  // Fallback for simulation / tests
  if (callback && typeof callback.onSuccess === 'function') {
    callback.onSuccess({ code: 0, msg: 'Message sent (simulation)' });
  }
};

/**
 * Pings the paired phone to test connection status.
 * @param {object} [callback] - Callback object with onSuccess and onFailure
 */
P2pClient.prototype.ping = function(callback) {
  if (nativeWear && typeof nativeWear.ping === 'function') {
    try {
      nativeWear.ping({
        pkgName: this.peerPkgName,
        fingerPrint: this.peerFingerPrint,
        onSuccess: function(data) {
          if (callback && typeof callback.onSuccess === 'function') {
            callback.onSuccess(data);
          }
        },
        onFailure: function(errCode, errDesc) {
          if (callback && typeof callback.onFailure === 'function') {
            callback.onFailure(errCode, errDesc);
          }
        }
      });
    } catch (err) {
      console.warn('nativeWear.ping failed:', err);
      if (callback && typeof callback.onFailure === 'function') {
        callback.onFailure(-1, err && err.message ? err.message : 'ping failed');
      }
    }
    return;
  }

  if (callback && typeof callback.onSuccess === 'function') {
    callback.onSuccess({ code: 0, msg: 'Ping success (simulation)' });
  }
};

/**
 * Convenience factory to create a P2pClient instance.
 * @returns {P2pClient}
 */
export function getP2pClient() {
  return new P2pClient();
}

export default {
  Message: Message,
  Builder: Builder,
  P2pClient: P2pClient,
  getP2pClient: getP2pClient
};
