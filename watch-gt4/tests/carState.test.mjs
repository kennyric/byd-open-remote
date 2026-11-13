import test, { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  createInitialState,
  stepTemp,
  buildCommand,
  applyResponse
} from '../entry/src/main/js/default/common/carState.js';

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
  });

  describe('applyResponse(state, rawJson)', () => {
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
});
