import test, { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  createInitialState,
  stepTemp,
  buildCommand,
  applyResponse
} from '../entry/src/main/js/default/common/carState.js';
import {
  P2pClient,
  Message,
  Builder,
  getP2pClient
} from '../entry/src/main/js/default/wearengine/wearengine.js';

describe('carState module', () => {
  describe('createInitialState()', () => {
    it('returns exact initial state specified in design contract', () => {
      const state = createInitialState();
      assert.deepStrictEqual(state, {
        soc: null,
        rangeKm: null,
        locked: true,
        acOn: false,
        cabinTempC: null,
        targetTempC: 17,
        ts: null,
        pending: false,
        confirmUnlock: false,
        phoneOnline: false,
        banner: ''
      });
    });

    it('returns a fresh object copy on each invocation', () => {
      const s1 = createInitialState();
      const s2 = createInitialState();
      assert.notEqual(s1, s2);
      s1.targetTempC = 25;
      assert.strictEqual(s2.targetTempC, 17);
    });
  });

  describe('stepTemp(state, delta)', () => {
    it('increases temperature within valid bounds', () => {
      const state = { targetTempC: 17 };
      const next = stepTemp(state, 1);
      assert.strictEqual(next.targetTempC, 18);
    });

    it('decreases temperature within valid bounds', () => {
      const state = { targetTempC: 22 };
      const next = stepTemp(state, -1);
      assert.strictEqual(next.targetTempC, 21);
    });

    it('clamps to lower bound 17°C', () => {
      const state = { targetTempC: 17 };
      const next = stepTemp(state, -1);
      assert.strictEqual(next.targetTempC, 17);

      const nextLarge = stepTemp({ targetTempC: 19 }, -10);
      assert.strictEqual(nextLarge.targetTempC, 17);
    });

    it('clamps to upper bound 31°C', () => {
      const state = { targetTempC: 31 };
      const next = stepTemp(state, 1);
      assert.strictEqual(next.targetTempC, 31);

      const nextLarge = stepTemp({ targetTempC: 28 }, 10);
      assert.strictEqual(nextLarge.targetTempC, 31);
    });

    it('clamps out-of-range input state.targetTempC (e.g. 10 -> 17, 40 -> 31)', () => {
      assert.strictEqual(stepTemp({ targetTempC: 10 }, 0).targetTempC, 17);
      assert.strictEqual(stepTemp({ targetTempC: 10 }, 2).targetTempC, 19);
      assert.strictEqual(stepTemp({ targetTempC: 40 }, 0).targetTempC, 31);
      assert.strictEqual(stepTemp({ targetTempC: 40 }, -2).targetTempC, 29);
    });

    it('does not mutate input state', () => {
      const state = { targetTempC: 20, soc: 80 };
      const next = stepTemp(state, 2);
      assert.strictEqual(state.targetTempC, 20);
      assert.strictEqual(next.targetTempC, 22);
      assert.strictEqual(next.soc, 80);
      assert.notEqual(state, next);
    });

    it('handles non-numeric or missing targetTempC safely by defaulting to 17', () => {
      assert.strictEqual(stepTemp({}, 2).targetTempC, 19);
      assert.strictEqual(stepTemp(null, 1).targetTempC, 18);
      assert.strictEqual(stepTemp({ targetTempC: 'invalid' }, -1).targetTempC, 17);
    });

    it('handles non-numeric delta safely by returning unchanged targetTempC', () => {
      const state = { targetTempC: 23 };
      assert.strictEqual(stepTemp(state, 'abc').targetTempC, 23);
      assert.strictEqual(stepTemp(state, null).targetTempC, 23);
      assert.strictEqual(stepTemp(state, undefined).targetTempC, 23);
    });

    it('handles Infinity and -Infinity safely in delta and targetTempC', () => {
      const state = { targetTempC: 22 };
      assert.strictEqual(stepTemp(state, Infinity).targetTempC, 22);
      assert.strictEqual(stepTemp(state, -Infinity).targetTempC, 22);
      assert.strictEqual(stepTemp(state, NaN).targetTempC, 22);

      assert.strictEqual(stepTemp({ targetTempC: Infinity }, 1).targetTempC, 18);
      assert.strictEqual(stepTemp({ targetTempC: -Infinity }, 1).targetTempC, 18);
    });
  });

  describe('buildCommand(action, state)', () => {
    it('produces valid JSON string with id and action for simple commands', () => {
      const actions = ['status', 'lock', 'unlock', 'lights', 'horn', 'ac_off'];
      for (const action of actions) {
        const raw = buildCommand(action);
        assert.strictEqual(typeof raw, 'string');
        const parsed = JSON.parse(raw);
        assert.strictEqual(typeof parsed.id, 'string');
        assert.ok(parsed.id.length > 0);
        assert.strictEqual(parsed.action, action);
        assert.strictEqual(parsed.tempC, undefined);
      }
    });

    it('includes tempC from state.targetTempC when action is ac_on', () => {
      const state = { targetTempC: 22 };
      const raw = buildCommand('ac_on', state);
      const parsed = JSON.parse(raw);
      assert.strictEqual(parsed.action, 'ac_on');
      assert.strictEqual(parsed.tempC, 22);
      assert.strictEqual(typeof parsed.id, 'string');
    });

    it('defaults tempC to 17 when action is ac_on and state is missing or lacks targetTempC', () => {
      const rawWithoutState = buildCommand('ac_on');
      const parsedWithoutState = JSON.parse(rawWithoutState);
      assert.strictEqual(parsedWithoutState.action, 'ac_on');
      assert.strictEqual(parsedWithoutState.tempC, 17);

      const rawEmptyState = buildCommand('ac_on', {});
      const parsedEmptyState = JSON.parse(rawEmptyState);
      assert.strictEqual(parsedEmptyState.tempC, 17);

      const rawInvalidTemp = buildCommand('ac_on', { targetTempC: 'hot' });
      const parsedInvalidTemp = JSON.parse(rawInvalidTemp);
      assert.strictEqual(parsedInvalidTemp.tempC, 17);
    });

    it('clamps out-of-range targetTempC and handles Infinity in buildCommand(ac_on)', () => {
      const rawLow = buildCommand('ac_on', { targetTempC: 10 });
      assert.strictEqual(JSON.parse(rawLow).tempC, 17);

      const rawHigh = buildCommand('ac_on', { targetTempC: 45 });
      assert.strictEqual(JSON.parse(rawHigh).tempC, 31);

      const rawInf = buildCommand('ac_on', { targetTempC: Infinity });
      assert.strictEqual(JSON.parse(rawInf).tempC, 17);

      const rawNegInf = buildCommand('ac_on', { targetTempC: -Infinity });
      assert.strictEqual(JSON.parse(rawNegInf).tempC, 17);
    });
  });

  describe('applyResponse(state, rawJson, expectedId)', () => {
    const baseState = createInitialState();

    it('updates telemetry fields from nested response.state', () => {
      const responsePayload = JSON.stringify({
        id: '1700000001',
        ok: true,
        action: 'status',
        msg: 'Updated',
        state: {
          soc: 78,
          rangeKm: 290,
          locked: true,
          acOn: false,
          cabinTempC: 27,
          targetTempC: 19,
          ts: 1700000002
        }
      });

      const next = applyResponse(baseState, responsePayload);
      assert.strictEqual(next.soc, 78);
      assert.strictEqual(next.rangeKm, 290);
      assert.strictEqual(next.locked, true);
      assert.strictEqual(next.acOn, false);
      assert.strictEqual(next.cabinTempC, 27);
      assert.strictEqual(next.targetTempC, 19);
      assert.strictEqual(next.ts, 1700000002);
      assert.strictEqual(next.phoneOnline, true);
      assert.strictEqual(next.pending, false);
      assert.strictEqual(next.confirmUnlock, false);
      assert.strictEqual(next.banner, '');
    });

    it('also supports flat response object without nested state key', () => {
      const flatPayload = JSON.stringify({
        id: '1700000002',
        ok: true,
        action: 'lock',
        soc: 85,
        rangeKm: 310,
        locked: true,
        acOn: true,
        cabinTempC: 22,
        targetTempC: 20,
        ts: 1700000010
      });

      const next = applyResponse(baseState, flatPayload);
      assert.strictEqual(next.soc, 85);
      assert.strictEqual(next.rangeKm, 310);
      assert.strictEqual(next.locked, true);
      assert.strictEqual(next.acOn, true);
      assert.strictEqual(next.cabinTempC, 22);
      assert.strictEqual(next.targetTempC, 20);
      assert.strictEqual(next.ts, 1700000010);
      assert.strictEqual(next.phoneOnline, true);
    });

    it('preserves existing targetTempC when response does not specify a valid targetTempC', () => {
      const stateWithTemp = { ...baseState, targetTempC: 24 };
      const payload = JSON.stringify({
        ok: true,
        state: { soc: 50, rangeKm: 200 }
      });
      const next = applyResponse(stateWithTemp, payload);
      assert.strictEqual(next.targetTempC, 24);
      assert.strictEqual(next.soc, 50);
    });

    it('rejects out-of-range targetTempC (16, 32, Infinity) in response', () => {
      const stateWithTemp = { ...baseState, targetTempC: 21 };

      const lowPayload = JSON.stringify({ ok: true, state: { targetTempC: 16 } });
      assert.strictEqual(applyResponse(stateWithTemp, lowPayload).targetTempC, 21);

      const highPayload = JSON.stringify({ ok: true, state: { targetTempC: 32 } });
      assert.strictEqual(applyResponse(stateWithTemp, highPayload).targetTempC, 21);

      const infPayload = JSON.stringify({ ok: true, state: { targetTempC: Infinity } });
      assert.strictEqual(applyResponse(stateWithTemp, infPayload).targetTempC, 21);
    });

    it('handles falsy and negative telemetry values correctly (0, negative cabin temp, null reset)', () => {
      const stateWithCabin = { ...baseState, cabinTempC: 25, soc: 80 };

      const falsyPayload = JSON.stringify({
        ok: true,
        state: {
          soc: 0,
          rangeKm: 0,
          cabinTempC: 0,
          locked: false,
          acOn: false,
          ts: 0
        }
      });
      const nextFalsy = applyResponse(stateWithCabin, falsyPayload);
      assert.strictEqual(nextFalsy.soc, 0);
      assert.strictEqual(nextFalsy.rangeKm, 0);
      assert.strictEqual(nextFalsy.cabinTempC, 0);
      assert.strictEqual(nextFalsy.locked, false);
      assert.strictEqual(nextFalsy.acOn, false);
      assert.strictEqual(nextFalsy.ts, 0);

      const subzeroPayload = JSON.stringify({
        ok: true,
        state: { cabinTempC: -5 }
      });
      const nextSubzero = applyResponse(stateWithCabin, subzeroPayload);
      assert.strictEqual(nextSubzero.cabinTempC, -5);

      const nullCabinPayload = JSON.stringify({
        ok: true,
        state: { cabinTempC: null }
      });
      const nextNull = applyResponse(stateWithCabin, nullCabinPayload);
      assert.strictEqual(nextNull.cabinTempC, null);
    });

    it('supports expectedId correlation and ignores stale mismatched responses', () => {
      const matchingPayload = JSON.stringify({
        id: 'cmd_123',
        ok: true,
        state: { soc: 95 }
      });
      const nextMatch = applyResponse(baseState, matchingPayload, 'cmd_123');
      assert.strictEqual(nextMatch.soc, 95);

      const stalePayload = JSON.stringify({
        id: 'cmd_stale_old',
        ok: true,
        state: { soc: 40 }
      });
      const nextMismatched = applyResponse(baseState, stalePayload, 'cmd_expected_new');
      assert.strictEqual(nextMismatched.soc, null);
      assert.deepStrictEqual(nextMismatched, baseState);
    });

    it('clears pending and confirmUnlock flags on response', () => {
      const pendingState = {
        ...baseState,
        pending: true,
        confirmUnlock: true,
        banner: 'Previous error'
      };
      const payload = JSON.stringify({ ok: true, action: 'status' });
      const next = applyResponse(pendingState, payload);
      assert.strictEqual(next.pending, false);
      assert.strictEqual(next.confirmUnlock, false);
      assert.strictEqual(next.banner, '');
    });

    it('does not mutate the input state', () => {
      const frozenState = Object.freeze({ ...baseState, pending: true });
      const payload = JSON.stringify({ ok: true, state: { soc: 90 } });
      const next = applyResponse(frozenState, payload);
      assert.strictEqual(next.soc, 90);
      assert.strictEqual(frozenState.soc, null);
    });

    describe('error banners (ok === false)', () => {
      it('maps PIN_INVALID to friendly banner', () => {
        const payload = JSON.stringify({ ok: false, err: 'PIN_INVALID' });
        const next = applyResponse(baseState, payload);
        assert.match(next.banner, /PIN/i);
        assert.strictEqual(next.phoneOnline, true);
        assert.strictEqual(next.pending, false);
      });

      it('maps AUTH_FAILED to friendly banner', () => {
        const payload = JSON.stringify({ ok: false, err: 'AUTH_FAILED' });
        const next = applyResponse(baseState, payload);
        assert.match(next.banner, /Auth/i);
      });

      it('maps CAR_OFFLINE to friendly banner', () => {
        const payload = JSON.stringify({ ok: false, err: 'CAR_OFFLINE' });
        const next = applyResponse(baseState, payload);
        assert.match(next.banner, /Offline/i);
      });

      it('maps NOT_CONFIGURED to friendly banner', () => {
        const payload = JSON.stringify({ ok: false, err: 'NOT_CONFIGURED' });
        const next = applyResponse(baseState, payload);
        assert.match(next.banner, /Config/i);
      });

      it('maps TIMEOUT to friendly banner', () => {
        const payload = JSON.stringify({ ok: false, err: 'TIMEOUT' });
        const next = applyResponse(baseState, payload);
        assert.match(next.banner, /Timeout/i);
      });

      it('uses msg if provided for generic/unknown error', () => {
        const payload = JSON.stringify({ ok: false, msg: 'Bluetooth disconnected' });
        const next = applyResponse(baseState, payload);
        assert.strictEqual(next.banner, 'Bluetooth disconnected');
      });

      it('uses raw err if no msg and err is unknown', () => {
        const payload = JSON.stringify({ ok: false, err: 'CUSTOM_ERR' });
        const next = applyResponse(baseState, payload);
        assert.strictEqual(next.banner, 'CUSTOM_ERR');
      });

      it('guards against Object.prototype collision keys in err (toString, constructor)', () => {
        const payloadToString = JSON.stringify({ ok: false, err: 'toString' });
        const next1 = applyResponse(baseState, payloadToString);
        assert.strictEqual(next1.banner, 'toString');

        const payloadCtor = JSON.stringify({ ok: false, err: 'constructor' });
        const next2 = applyResponse(baseState, payloadCtor);
        assert.strictEqual(next2.banner, 'constructor');
      });

      it('provides default fallback banner when neither err nor msg provided', () => {
        const payload = JSON.stringify({ ok: false });
        const next = applyResponse(baseState, payload);
        assert.ok(next.banner.length > 0);
      });
    });

    describe('malformed and edge-case payload resilience', () => {
      it('safely handles non-JSON strings without throwing', () => {
        const next = applyResponse(baseState, 'not json at all');
        assert.deepStrictEqual(next, baseState);
      });

      it('safely handles null/undefined/empty string without throwing', () => {
        assert.deepStrictEqual(applyResponse(baseState, null), baseState);
        assert.deepStrictEqual(applyResponse(baseState, undefined), baseState);
        assert.deepStrictEqual(applyResponse(baseState, ''), baseState);
      });

      it('safely handles non-object JSON (numbers, booleans, arrays) without throwing', () => {
        assert.deepStrictEqual(applyResponse(baseState, '123'), baseState);
        assert.deepStrictEqual(applyResponse(baseState, 'true'), baseState);
        assert.deepStrictEqual(applyResponse(baseState, '["an", "array"]'), baseState);
      });
    });
  });

  describe('wearengine wrapper module', () => {
    it('exports Builder, Message, P2pClient, and getP2pClient at top level', () => {
      assert.strictEqual(typeof Builder, 'function');
      assert.strictEqual(typeof Message, 'function');
      assert.strictEqual(typeof P2pClient, 'function');
      assert.strictEqual(typeof getP2pClient, 'function');
      assert.strictEqual(Message.Builder, Builder);
    });

    it('builds messages with Builder chaining and property setters', () => {
      const builder = new Builder()
        .setData('data-payload')
        .setDescription('desc-payload')
        .setPayload('custom-payload')
        .setType(1);

      assert.strictEqual(builder.data, 'data-payload');
      assert.strictEqual(builder.description, 'desc-payload');
      assert.strictEqual(builder.payload, 'custom-payload');
      assert.strictEqual(builder.type, 1);

      const msg = builder.build();
      assert.ok(msg instanceof Message);
      assert.strictEqual(msg.data, 'data-payload');
      assert.strictEqual(msg.description, 'desc-payload');
      assert.strictEqual(msg.payload, 'custom-payload');
      assert.strictEqual(msg.type, 1);
      assert.strictEqual(msg.builder, builder);
    });

    it('setPeerFingerPrint strips colons from hex fingerprint string', () => {
      const client = getP2pClient();
      client.setPeerFingerPrint('11:22:33:44:55:66:AA:BB:CC');
      assert.strictEqual(client.peerFingerPrint, '112233445566AABBCC');
    });

    it('handles registerReceiver and unregisterReceiver in simulation mode', () => {
      const client = getP2pClient();
      let registered = false;
      client.registerReceiver({
        onSuccess: (res) => {
          registered = true;
          assert.strictEqual(res.code, 0);
        }
      });
      assert.strictEqual(registered, true);
      assert.strictEqual(client.isRegistered, true);

      client.unregisterReceiver();
      assert.strictEqual(client.isRegistered, false);
      assert.strictEqual(client.receiver, null);
    });

    it('send handles string, Message with data, Builder, and description payloads in simulation mode', () => {
      const client = getP2pClient();
      let sentCount = 0;
      const callback = {
        onSuccess: () => {
          sentCount += 1;
        }
      };

      // Raw string
      client.send('raw-string', callback);
      // Message with data
      client.send(new Builder().setData('data-msg').build(), callback);
      // Message with description
      client.send(new Builder().setDescription('desc-msg').build(), callback);
      // Object with builder
      client.send({ builder: { description: 'builder-desc' } }, callback);
      // Object with payload
      client.send({ payload: 'payload-field' }, callback);

      assert.strictEqual(sentCount, 5);
    });

    it('ping invokes onSuccess in simulation fallback', () => {
      const client = getP2pClient();
      let pinged = false;
      client.ping({
        onSuccess: (res) => {
          pinged = true;
          assert.strictEqual(res.code, 0);
        }
      });
      assert.strictEqual(pinged, true);
    });
  });
});
