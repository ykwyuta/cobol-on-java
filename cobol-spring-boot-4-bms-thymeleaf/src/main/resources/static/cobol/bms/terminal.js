// BMS 画面の端末操作 (設計 77 §4.5.3、設計 81)。
// 配置は server が決めるので、ここは表示を作り直さない。MDT、cursor、AID の送信、二重送信の抑止だけを扱う。
// これが読めなくても画面は崩れず、Enter / Clear / PF の button で送信できる。

const form = document.querySelector('form.bms-terminal');

if (form) {
  const columns = Number(form.dataset.columns);
  const inputs = [...form.querySelectorAll('input.bms-field')];

  // 最初の利用者変更で MDT を立てる
  for (const input of inputs) {
    input.addEventListener('input', () => {
      input.dataset.modified = 'true';
    });
    if (input.dataset.numeric === 'true') {
      // 3270 の数字 lock が受ける文字だけを通す
      input.addEventListener('beforeinput', (event) => {
        if (event.data && /[^0-9.\-]/.test(event.data)) {
          event.preventDefault();
        }
      });
    }
  }

  // 初期 cursor: server の指定位置を含む field、なければ最初の非保護 field
  const cursorRow = Number(form.dataset.cursorRow);
  const cursorColumn = Number(form.dataset.cursorColumn);
  const atCursor = inputs.find((input) => Number(input.dataset.row) === cursorRow
    && cursorColumn >= Number(input.dataset.column)
    && cursorColumn < Number(input.dataset.column) + Number(input.dataset.length));
  const initial = atCursor || inputs[0];
  if (initial) {
    initial.focus();
    if (atCursor) {
      const offset = cursorColumn - Number(atCursor.dataset.column);
      atCursor.setSelectionRange(offset, offset);
    }
  }

  // PF1〜PF12 は F1〜F12、PF13〜PF24 は Shift+F1〜F12。ブラウザの既定動作は terminal focus 中だけ抑止する
  form.addEventListener('keydown', (event) => {
    const match = /^F(\d{1,2})$/.exec(event.key);
    if (!match) {
      return;
    }
    const number = Number(match[1]) + (event.shiftKey ? 12 : 0);
    if (number < 1 || number > 24) {
      return;
    }
    event.preventDefault();
    submitWith(`PF${number}`);
  });

  form.addEventListener('submit', (event) => {
    if (form.dataset.locked === 'true') {
      event.preventDefault();
      return;
    }
    const aid = event.submitter && event.submitter.name === 'aid' ? event.submitter.value : 'ENTER';
    prepare(aid);
  });

  function submitWith(aid) {
    if (form.dataset.locked === 'true') {
      return;
    }
    const hidden = document.createElement('input');
    hidden.type = 'hidden';
    hidden.name = 'aid';
    hidden.value = aid;
    form.appendChild(hidden);
    prepare(aid);
    form.submit();
  }

  function prepare(aid) {
    // cursor 位置を画面先頭からの offset で送る
    const cursor = form.querySelector('input[name="cursor"]');
    const active = document.activeElement;
    if (inputs.includes(active)) {
      const row = Number(active.dataset.row) - 1;
      const column = Number(active.dataset.column) - 1 + (active.selectionStart || 0);
      cursor.value = String(row * columns + column);
    }
    // 変更した field と FSET の field だけを送る。CLEAR と PA は field を送らない
    const shortRead = aid === 'CLEAR' || /^PA[1-3]$/.test(aid);
    for (const input of inputs) {
      const send = !shortRead && (input.dataset.modified === 'true' || input.dataset.fset === 'true');
      input.disabled = !send;
    }
    // AID 送信後は keyboard を lock して二重送信を防ぐ
    form.dataset.locked = 'true';
  }
}

// 端末へ出す task (START TERMID、ATI) が画面を書き換えたら、server が SSE で版を知らせる (設計 83 §7)。
// 受けたら現在の画面を読み直す。EventSource が使えないか切れたままでも、次の送信で server が古い版の入力を動かさず
// 現在の画面を返すので、画面と入力は食い違わない。
const events = document.body.dataset.terminalEvents;
const current = document.body.dataset.terminalScreen;
const version = document.body.dataset.screenVersion;
if (events && current && version !== undefined && typeof EventSource === 'function') {
  const source = new EventSource(`${events}?version=${encodeURIComponent(version)}`);
  source.addEventListener('screen', () => {
    source.close();
    // 送信の途中なら、その応答が現在の画面を返す
    if (!form || form.dataset.locked !== 'true') {
      window.location.assign(current);
    }
  });
}
