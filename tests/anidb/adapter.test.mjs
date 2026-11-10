import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';

const source = await readFile(new URL('../../app/src/main/assets/anidb-adapter.js', import.meta.url), 'utf8');
const fixture = await readFile(new URL('./fixture.html', import.meta.url), 'utf8');
function setup(url = 'https://anidb.net/anime/1/release/add') {
  const dom = new JSDOM(fixture, { url, runScripts: 'outside-only' });
  const w = dom.window, d = w.document;
  const events = { submits: 0, next: 0, final: 0, search: 0 };
  d.querySelector('form').addEventListener('submit', e => { e.preventDefault(); events.submits++; });
  d.getElementById('addfilem_next').addEventListener('click', () => events.next++);
  d.getElementById('addfilem_search').addEventListener('click', () => {
    events.search++;
    const option = d.createElement('option'); option.value = 'new'; option.textContent = 'Searched Group';
    d.getElementById('addfilem_group').append(option);
  });
  function addTrack(kind) {
    const container = d.getElementById(kind === 'aud' ? 'audio' : 'subtitle');
    const word = kind === 'aud' ? 'audio' : 'subtitle';
    const n = container.children.length + 1;
    const row = d.createElement('div');
    row.innerHTML = `<button type="button" id="del_${word}_${n}">Remove</button><select id="addstrm_${kind}_${n}_type">${kind === 'aud' ? '<option value="normal">normal audio</option><option value="commentary">commentary audio</option>' : '<option value="soft">soft sub</option><option value="hard">hard sub</option>'}</select><select id="addstrm_${kind}_${n}_lang"><option value="${kind === 'aud' ? 'ja' : 'en'}">${kind === 'aud' ? 'japanese' : 'english'}</option><option value="fr">french</option></select>${kind === 'sub' ? '<input type="checkbox" id="addstrm_sub_' + n + '_flag_FLAG_FORCOMMENTARY"><input type="checkbox" id="addstrm_sub_' + n + '_flag_FLAG_DUBSUBBED"><input type="checkbox" id="addstrm_sub_' + n + '_flag_FLAG_HEARINGIMP">' : ''}`;
    row.querySelector('button').addEventListener('click', () => row.remove());
    container.append(row);
  }
  d.getElementById('add_audio').addEventListener('click', () => addTrack('aud'));
  d.getElementById('add_subtitle').addEventListener('click', () => addTrack('sub'));
  addTrack('aud'); addTrack('sub');
  w.eval(source);
  return { dom, w, d, adapter: w.__shokoAniDbV1, events };
}

test('reads current options, disabled values, defaults, and all observed field families', () => {
  const { adapter } = setup();
  const s = adapter.snapshot();
  assert.equal(s.status, 'form');
  assert.equal(s.audioTracks, 1); assert.equal(s.subtitleTracks, 1);
  assert.equal(s.fields.find(f => f.id === 'addfilem_type').value, 'unknown');
  assert.equal(s.fields.find(f => f.id === 'addfilem_quality').options.find(o => o.value === 'disabled').disabled, true);
  assert.equal(s.fields.filter(f => f.kind === 'checkbox').length, 7);
});

test('fills primary and advanced fields, expands tracks with page controls, preserves hidden state', () => {
  const { adapter, d, events } = setup();
  const edits = {
    audioTracks: 2, subtitleTracks: 2,
    values: { addfilem_group: '42', addfilem_template: 'fansub', addf_crcstatus: '1', addfilem_quality: 'high', addfilem_type: 'www', addfilem_ed2k: 'ed2k://|file|fixture.mkv|10|0123456789abcdef0123456789abcdef|/', addstrm_aud_2_type: 'commentary', addstrm_aud_2_lang: 'fr', addstrm_sub_2_type: 'hard', addstrm_sub_2_lang: 'fr' },
    checks: { addstrm_vid_flag_FLAG_CLEAN: true, addstrm_sub_2_flag_FLAG_HEARINGIMP: true },
  };
  const result = adapter.apply(edits);
  assert.equal(result.ok, true);
  for (const [id, value] of Object.entries(edits.values)) assert.equal(d.getElementById(id).value, value);
  for (const [id, value] of Object.entries(edits.checks)) assert.equal(d.getElementById(id).checked, value);
  assert.equal(d.querySelector('[name="csrf"]').value, 'fixture-token');
  assert.equal(events.next, 0); assert.equal(events.submits, 0);
  assert.equal(adapter.apply({ audioTracks: 1, subtitleTracks: 0 }).ok, true);
});

test('refuses unknown fields and never drops newly introduced AniDB controls', () => {
  const { adapter, d } = setup();
  const unknown = d.createElement('input'); unknown.id = 'new-anidb-feature'; d.querySelector('form').append(unknown);
  assert.equal(adapter.snapshot().status, 'unsupported');
  assert.equal(adapter.apply({ values: { addfilem_group: '42' } }).ok, false);
  assert.equal(d.getElementById('addfilem_group').value, '0');
});

test('preserves hidden AniDB source metadata without exposing or editing it', () => {
  const { adapter, d, events } = setup();
  const metadata = ['addfilem_episodes', 'addfilem_anime_title'].map(id => d.getElementById(id));
  const before = metadata.map(element => element.value);
  let changes = 0;
  metadata.forEach(element => {
    element.addEventListener('input', () => changes++);
    element.addEventListener('change', () => changes++);
  });
  const snapshot = adapter.snapshot();
  assert.equal(snapshot.status, 'form');
  assert.ok(metadata.every(element => !snapshot.fields.some(field => field.id === element.id)));
  assert.equal(adapter.apply({ values: { addfilem_group: '42' } }).ok, true);
  for (const element of metadata) assert.equal(adapter.apply({ values: { [element.id]: 'changed' } }).ok, false);
  assert.deepEqual(metadata.map(element => element.value), before);
  assert.equal(changes, 0);
  assert.equal(events.next, 0); assert.equal(events.submits, 0);
});

test('accepts only the known metadata IDs when hidden on the control or an ancestor', () => {
  const hiddenCases = [
    element => { element.hidden = true; },
    element => { element.style.display = 'none'; },
    element => { element.style.visibility = 'hidden'; },
    (element, d) => { const parent = d.createElement('div'); parent.style.display = 'none'; element.replaceWith(parent); parent.append(element); },
    (element, d) => { const parent = d.createElement('div'); parent.style.visibility = 'hidden'; element.replaceWith(parent); parent.append(element); },
  ];
  for (const hide of hiddenCases) {
    const { adapter, d } = setup();
    for (const id of ['addfilem_episodes', 'addfilem_anime_title']) {
      const element = d.getElementById(id);
      d.querySelector('form').append(element);
      hide(element, d);
    }
    assert.equal(adapter.snapshot().status, 'form');
    const unknown = d.createElement('input'); unknown.id = 'new-anidb-feature';
    d.querySelector('form').append(unknown); hide(unknown, d);
    const snapshot = adapter.snapshot();
    assert.equal(snapshot.status, 'unsupported');
    assert.ok(snapshot.unsupported.some(message => message.includes('new-anidb-feature') && message.includes('hidden=true')));
  }
});

test('rejects the metadata IDs if exposed as visible editable controls', () => {
  for (const id of ['addfilem_episodes', 'addfilem_anime_title']) {
    const { adapter, d } = setup();
    const element = d.getElementById(id);
    d.querySelector('form').append(element);
    const snapshot = adapter.snapshot();
    assert.equal(snapshot.status, 'unsupported');
    assert.ok(snapshot.unsupported.includes('Unmapped field: ' + id + ' (type=' + element.type + ', readOnly=false, disabled=false, hidden=false)'));
    assert.equal(adapter.apply({ values: { addfilem_group: '42' } }).ok, false);
    assert.equal(d.getElementById('addfilem_group').value, '0');
  }
});

test('rejects bad choices and unexpected native IDs', () => {
  const { adapter, events } = setup();
  assert.equal(adapter.apply({ values: { addfilem_quality: 'disabled' } }).ok, false);
  assert.equal(adapter.apply({ values: { addfilem_quality: 'nonexistent' } }).ok, false);
  assert.equal(adapter.apply({ values: { addfilem_add_files: 'submit' } }).ok, false);
  assert.equal(events.submits, 0); assert.equal(events.next, 0);
});

test('unchanged disabled controls are preserved without events; changed disabled values are rejected', () => {
  const { adapter, d, events } = setup();
  const quality = d.getElementById('addfilem_quality');
  const clean = d.getElementById('addstrm_vid_flag_FLAG_CLEAN');
  quality.disabled = true; clean.disabled = true;
  let changes = 0;
  quality.addEventListener('change', () => changes++);
  clean.addEventListener('change', () => changes++);
  assert.equal(adapter.apply({ values: { addfilem_quality: quality.value }, checks: { addstrm_vid_flag_FLAG_CLEAN: clean.checked } }).ok, true);
  assert.equal(changes, 0);
  assert.equal(adapter.apply({ values: { addfilem_quality: 'high' } }).ok, false);
  assert.equal(adapter.apply({ checks: { addstrm_vid_flag_FLAG_CLEAN: !clean.checked } }).ok, false);
  assert.equal(events.submits, 0);
});

test('reapplying the same template does not reset manually changed track defaults', () => {
  const { adapter, d } = setup();
  let templateChanges = 0;
  d.getElementById('addfilem_template').addEventListener('change', () => templateChanges++);
  assert.equal(adapter.apply({ values: { addfilem_template: 'manual', addfilem_type: 'www' } }).ok, true);
  assert.equal(templateChanges, 0);
  assert.equal(d.getElementById('addfilem_type').value, 'www');
  assert.equal(adapter.apply({ values: { addfilem_template: 'fansub' } }).ok, true);
  assert.equal(templateChanges, 1);
});

test('searches through official controls and refreshes group choices', () => {
  const { adapter, d, events } = setup();
  assert.equal(adapter.searchGroups('Example').ok, true);
  assert.equal(d.getElementById('addfilem_group_input').value, 'Example');
  assert.equal(events.search, 1);
  assert.ok(adapter.snapshot().fields.find(f => f.id === 'addfilem_group').options.some(o => o.value === 'new'));
  d.getElementById('addfilem_search').type = 'submit';
  assert.equal(adapter.searchGroups('Example').ok, false);
  assert.equal(events.submits, 0);
});

test('reads and edits per-file review without clicking final Add Files', () => {
  const { adapter, d, events } = setup();
  d.getElementById('addfilem_ed2k').remove();
  const output = d.createElement('table'); output.id = 'addfilem_output';
  output.innerHTML = '<tr><th>No</th><th>Filename</th><th>CRC</th><th>Ext.</th><th>Epno</th><th>version</th><th>Release Date</th></tr><tr><td>1</td><td>fixture.mkv</td><td>12345678</td><td>mkv</td><td><select><option value="1">1</option><option value="2">2</option></select></td><td><select><option value="1">v1</option><option value="2">v2</option></select></td><td><input value=""></td></tr><tr><td colspan="7"><input id="releasedate"><button type="button" id="applytoall">Apply to all</button></td></tr>';
  d.querySelector('form').append(output);
  const final = d.createElement('button'); final.id = 'addfilem_add_files'; final.type = 'submit'; final.textContent = 'Add Files';
  final.addEventListener('click', () => events.final++); d.querySelector('form').append(final);
  assert.equal(adapter.snapshot().status, 'review');
  assert.equal(adapter.snapshot().rows[0].filename, 'fixture.mkv');
  assert.equal(adapter.applyReview([{ index: 0, episode: '2', version: '2', releaseDate: '2026-11-08' }]).ok, true);
  const row = adapter.snapshot().rows[0];
  assert.equal(row.episode.value, '2'); assert.equal(row.version.value, '2'); assert.equal(row.releaseDate.value, '2026-11-08');
  assert.equal(events.final, 0); assert.equal(events.submits, 0); assert.equal(events.next, 0);
  assert.equal('submit' in adapter, false); assert.equal('next' in adapter, false);
});

test('rejects non-AniDB and cleartext origins', () => {
  for (const url of ['http://anidb.net/anime/1/release/add', 'https://anidb.net.attacker.test/anime/1/release/add']) {
    const { adapter } = setup(url);
    assert.equal(adapter.snapshot().status, 'unsupported');
    assert.equal(adapter.apply({ values: { addfilem_group: '42' } }).ok, false);
  }
});

test('refuses a changed cross-origin form action and missing required video control', () => {
  const { adapter, d } = setup();
  d.querySelector('form').action = 'https://example.invalid/collect';
  assert.equal(adapter.snapshot().status, 'unsupported');
  assert.equal(adapter.apply({ values: { addfilem_group: '42' } }).ok, false);
  d.querySelector('form').action = '/anime/1/release/add';
  d.getElementById('addstrm_vid_flag_FLAG_WRONGAR').remove();
  assert.equal(adapter.snapshot().status, 'unsupported');
});

test('identifies login separately from unknown pages and never infers submission success', () => {
  const { adapter, d } = setup();
  d.querySelector('form').remove();
  assert.equal(adapter.snapshot().status, 'page');
  const password = d.createElement('input'); password.type = 'password'; d.body.append(password);
  assert.equal(adapter.snapshot().status, 'login');
  password.remove(); d.title = 'Just a moment…';
  assert.equal(adapter.snapshot().status, 'challenge');
});
