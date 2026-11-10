/* Shoko Remote form adapter v1. This script never submits forms or clicks Next/Add Files. */
(function () {
  'use strict';
  if (window.__shokoAniDbV1) return;
  const VERSION = 1;
  const byId = id => document.getElementById(id);
  const text = element => element ? (element.textContent || '').trim().replace(/\s+/g, ' ') : '';
  const isAllowedOrigin = () => location.protocol === 'https:' && location.hostname === 'anidb.net' && (!location.port || location.port === '443');
  const baseIds = new Set(['addfilem_group', 'addfilem_group_input', 'addfilem_template', 'addf_crcstatus', 'addfilem_quality', 'addfilem_type', 'addfilem_ed2k']);
  const auxiliaryIds = new Set(['template_name', 'releasedate']);
  const metadataIds = new Set(['addfilem_episodes', 'addfilem_anime_title']);
  const fieldPattern = /^(?:addstrm_vid_flag_FLAG_(?:3DVIDEO|CLEAN|CREDITLESS|WRONGAR)|addstrm_aud_\d+_(?:type|lang)|addstrm_sub_\d+_(?:type|lang|flag_FLAG_(?:FORCOMMENTARY|DUBSUBBED|HEARINGIMP)))$/;
  const knownId = id => baseIds.has(id) || fieldPattern.test(id);
  const selectOptions = element => Array.from(element.options || []).map(option => ({
    value: option.value, label: text(option), disabled: !!option.disabled || !!(option.parentElement && option.parentElement.disabled),
  }));

  function isHidden(element) {
    for (let current = element; current; current = current.parentElement) {
      const style = window.getComputedStyle(current);
      if (current.hidden || style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return true;
    }
    return false;
  }

  function labelFor(element) {
    const explicit = element.id && document.querySelector('label[for="' + element.id + '"]');
    if (explicit) return text(explicit);
    const row = element.closest('tr');
    const heading = row && row.querySelector('th');
    let label = text(heading);
    if (element.type === 'checkbox') {
      const adjacent = element.nextSibling && element.nextSibling.nodeType === 3 ? element.nextSibling.textContent.trim() : '';
      label = adjacent || text(element.parentElement && element.parentElement.tagName === 'LABEL' ? element.parentElement : null) || element.id.split('FLAG_').pop();
    }
    if (/addstrm_.*_lang$/.test(element.id)) label += ' language';
    if (/addstrm_.*_type$/.test(element.id)) label += ' type';
    if (element.id === 'addfilem_quality') label = 'Quality';
    if (element.id === 'addfilem_type') label = 'Source';
    if (element.id === 'addfilem_group_input') label = 'Search groups';
    return label || element.id;
  }

  function describe(element, id, label) {
    return {
      id: id || element.id,
      kind: element.tagName === 'SELECT' ? 'select' : element.type === 'checkbox' ? 'checkbox' : element.tagName === 'TEXTAREA' ? 'multiline' : 'text',
      label: label || labelFor(element), value: element.value || '', checked: !!element.checked,
      disabled: !!element.disabled, options: selectOptions(element),
    };
  }

  function trackIndices(kind) {
    return Array.from(document.querySelectorAll('[id^="addstrm_' + kind + '_"]'))
      .map(element => element.id.match(new RegExp('^addstrm_' + kind + '_(\\d+)_type$')))
      .filter(Boolean).map(match => Number(match[1])).sort((a, b) => a - b);
  }

  function reviewRows() {
    const output = byId('addfilem_output');
    if (!output) return [];
    return Array.from(output.querySelectorAll('tr')).map(row => {
      const cells = Array.from(row.querySelectorAll('td'));
      if (!cells.length || !row.querySelector('select')) return [];
      const selects = row.querySelectorAll('select');
      const date = row.querySelector('input:not([type="hidden"]):not([type="button"]):not([type="submit"])');
      if (cells.length < 7 || selects.length !== 2 || !date) throw new Error('The AniDB review row layout has changed. Continue on the official page.');
      const index = Number(text(cells[0])) - 1;
      if (!Number.isInteger(index) || index < 0) throw new Error('Unrecognized AniDB review row.');
      return [{
        index, filename: text(cells[1]), crc: text(cells[2]), extension: text(cells[3]),
        episode: describe(selects[0], 'review:' + index + ':episode', 'Episode'),
        version: describe(selects[1], 'review:' + index + ':version', 'Version'),
        releaseDate: describe(date, 'review:' + index + ':releaseDate', 'Release date'),
      }];
    }).reduce((all, rows) => all.concat(rows), []);
  }

  function snapshot() {
    const result = { status: 'page', adapterVersion: VERSION, url: location.href, title: document.title || '', fields: [], rows: [], unsupported: [], audioTracks: 0, subtitleTracks: 0, message: '' };
    if (!isAllowedOrigin()) return { ...result, status: 'unsupported', message: 'Only the official HTTPS AniDB page can be edited.' };
    const form = byId('addfilem_form');
    if (!form) {
      if (document.querySelector('input[type="password"]')) result.status = 'login';
      else if (document.querySelector('#challenge-form, #cf-challenge-running, .cf-turnstile') || /just a moment|verify you are human|checking your browser/i.test(document.title)) result.status = 'challenge';
      result.message = result.status === 'login' ? 'Sign in on the official AniDB page. Your password stays in this WebView.' : result.status === 'challenge' ? 'AniDB is asking for a browser check. Complete it on the official page.' : 'Read the result on the official AniDB page. Navigation alone does not confirm that files were added.';
      return result;
    }
    result.status = byId('addfilem_output') ? 'review' : 'form';
    const required = ['addfilem_group', 'addfilem_group_input', 'addfilem_search', 'addfilem_template', 'addf_crcstatus', 'addfilem_quality', 'addfilem_type',
      'addstrm_vid_flag_FLAG_3DVIDEO', 'addstrm_vid_flag_FLAG_CLEAN', 'addstrm_vid_flag_FLAG_CREDITLESS', 'addstrm_vid_flag_FLAG_WRONGAR'];
    required.push(result.status === 'review' ? 'addfilem_add_files' : 'addfilem_ed2k');
    required.forEach(id => { if (!byId(id)) result.unsupported.push('Missing ' + id); });
    const action = new URL(form.action || location.href, location.href);
    if (action.origin !== location.origin) result.unsupported.push('The form action leaves AniDB.');
    Array.from(form.querySelectorAll('select, textarea, input')).forEach(element => {
      if (['hidden', 'button', 'submit', 'reset'].includes(element.type)) return;
      if (knownId(element.id)) result.fields.push(describe(element));
      else if (!auxiliaryIds.has(element.id) && !element.closest('#addfilem_output')) {
        const hidden = isHidden(element);
        // Recognized source metadata may be stored in CSS-hidden controls.
        // Preserve those controls on the page; they are never native edit targets.
        if (metadataIds.has(element.id) && hidden) return;
        result.unsupported.push('Unmapped field: ' + (element.id || element.name || element.tagName) +
          ' (type=' + element.type + ', readOnly=' + !!element.readOnly + ', disabled=' + !!element.disabled + ', hidden=' + hidden + ')');
      }
    });
    result.audioTracks = trackIndices('aud').length;
    result.subtitleTracks = trackIndices('sub').length;
    try { result.rows = reviewRows(); } catch (error) { result.unsupported.push(error.message); }
    if (result.unsupported.length) result.status = 'unsupported';
    result.message = result.status === 'unsupported' ? 'The AniDB form has changed. Use the official page so no options are lost.' : result.status === 'review' ? 'Review each file. The final Add Files button is operated only by you on AniDB.' : 'Native controls use the current AniDB form choices and defaults.';
    return result;
  }

  function editable() {
    if (!isAllowedOrigin()) throw new Error('AniDB origin changed.');
    const current = snapshot();
    if (!['form', 'review'].includes(current.status)) throw new Error(current.message);
    return current;
  }

  function checkValue(element, value) {
    if (!element) throw new Error('A requested AniDB control is missing.');
    if (element.disabled) {
      if (element.value === String(value)) return;
      throw new Error('AniDB disabled ' + element.id + '.');
    }
    if (element.tagName === 'SELECT' && !selectOptions(element).some(option => option.value === String(value) && !option.disabled)) throw new Error('AniDB does not offer the selected value for ' + element.id + '. Refresh the form.');
  }

  function setValue(element, value) {
    checkValue(element, value);
    if (element.disabled) return;
    element.value = String(value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function resizeTracks(kind, count) {
    if (count === null || count === undefined) return;
    if (!Number.isInteger(count) || count < 0 || count > 64) throw new Error('Invalid track count.');
    for (let steps = 0; steps < 65; steps++) {
      const indices = trackIndices(kind);
      if (indices.length === count) return;
      const id = indices.length < count ? (kind === 'aud' ? 'add_audio' : 'add_subtitle') : 'del_' + (kind === 'aud' ? 'audio' : 'subtitle') + '_' + indices[indices.length - 1];
      const control = byId(id);
      if (!control || control.disabled || control.getAttribute('aria-disabled') === 'true') throw new Error('AniDB does not permit that number of tracks.');
      control.click();
      if (trackIndices(kind).length === indices.length) throw new Error('AniDB could not change the track count. Continue on its official page.');
    }
    throw new Error('Track limit reached.');
  }

  function run(operation) {
    try { editable(); operation(); return { ok: true, snapshot: snapshot() }; }
    catch (error) { return { ok: false, error: String(error.message || error), snapshot: snapshot() }; }
  }

  function apply(edits) {
    return run(() => {
      const values = edits.values || {}, checks = edits.checks || {};
      // Reject unknown IDs before changing anything; templates can then materialize track fields.
      Object.keys(values).concat(Object.keys(checks)).forEach(id => { if (!knownId(id)) throw new Error('Unmapped control: ' + id); });
      if ('addfilem_template' in values && byId('addfilem_template').value !== values.addfilem_template) {
        setValue(byId('addfilem_template'), values.addfilem_template);
      }
      resizeTracks('aud', edits.audioTracks);
      resizeTracks('sub', edits.subtitleTracks);
      Object.entries(values).filter(([id]) => id !== 'addfilem_template').forEach(([id, value]) => checkValue(byId(id), value));
      Object.entries(checks).forEach(([id, checked]) => {
        const element = byId(id);
        if (!element || element.type !== 'checkbox') throw new Error('Invalid checkbox: ' + id);
        if (element.disabled && element.checked !== !!checked) throw new Error('AniDB disabled ' + id + '.');
      });
      Object.entries(values).filter(([id]) => id !== 'addfilem_template').forEach(([id, value]) => setValue(byId(id), value));
      Object.entries(checks).forEach(([id, checked]) => {
        const element = byId(id);
        if (element.disabled) return;
        checkValue(element, '');
        element.checked = !!checked;
        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
      });
    });
  }

  function applyReview(edits) {
    return run(() => {
      if (snapshot().status !== 'review') throw new Error('Open AniDB review with its Next button first.');
      const rows = Array.from(byId('addfilem_output').querySelectorAll('tr'));
      const assignments = edits.map(edit => {
        const row = rows.find(candidate => Number(text(candidate.querySelector('td'))) - 1 === edit.index);
        if (!row) throw new Error('AniDB review row is missing.');
        const selects = row.querySelectorAll('select');
        const date = row.querySelector('input:not([type="hidden"]):not([type="button"]):not([type="submit"])');
        checkValue(selects[0], edit.episode); checkValue(selects[1], edit.version); checkValue(date, edit.releaseDate);
        return { edit, selects, date };
      });
      assignments.forEach(({ edit, selects, date }) => { setValue(selects[0], edit.episode); setValue(selects[1], edit.version); setValue(date, edit.releaseDate); });
    });
  }

  function searchGroups(query) {
    return run(() => {
      if (typeof query !== 'string' || !query.trim()) throw new Error('Enter a group name.');
      setValue(byId('addfilem_group_input'), query.trim());
      const search = byId('addfilem_search');
      // A changed submit-button implementation cannot be assumed to be a read-only search.
      if (!search || search.type === 'submit') throw new Error('Use group Search on the official page.');
      search.click();
    });
  }

  // Deliberately no generic method, Next method, submit method, or native bridge.
  window.__shokoAniDbV1 = Object.freeze({ snapshot, apply, applyReview, searchGroups });
})();
