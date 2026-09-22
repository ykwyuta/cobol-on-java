// BMS 画面の端末操作 (設計 77 §4.5.3、設計 81)。
// 配置は server が決めるので、ここは表示を作り直さない。MDT、cursor、AID の送信、二重送信の抑止だけを扱う。
// これが読めなくても画面は崩れず、Enter / Clear / PF の button で送信できる。

const form = document.querySelector('form.bms-terminal');

if (form) {
  const columns = Number(form.dataset.columns);
  const inputs = [...form.querySelectorAll('input.bms-field')];

  // 端末が受け付ける文字 (設計 81 §5.1)。server が対象コードページから作った一覧を読む。
  // 読めるまでと、読めなかったときは見積りで代える。どちらでも判定そのものは server が行う
  let repertoire = null;
  const repertoireUrl = document.body.dataset.terminalCodepage;
  if (repertoireUrl) {
    fetch(repertoireUrl, { credentials: 'same-origin', headers: { accept: 'application/json' } })
      .then((response) => (response.ok ? response.json() : null))
      .then((body) => {
        if (body) {
          repertoire = {
            shifted: body.shifted === true,
            single: parseRanges(body.single),
            double: parseRanges(body.double),
          };
          for (const input of inputs) {
            // 読む前に入っていた値も見直す。画面から来た値は server が作ったものなので、
            // ふつうはここで変わらない
            enforce(input);
          }
          // 一覧が入ったことを DOM に残す。ブラウザの試験がこれを待つ
          form.dataset.codepage = body.codePage;
        }
      })
      .catch(() => {
        // 一覧が無くても画面は動く。入らない文字は server が断る
      });
  }

  // 最初の利用者変更で MDT を立てる
  for (const input of inputs) {
    input.addEventListener('input', (event) => {
      input.dataset.modified = 'true';
      // 変換中に値を書き換えると IME が壊れる。確定を待つ
      if (!event.isComposing) {
        enforce(input);
      }
    });
    // IME の確定は beforeinput を取り消せないことがある (cancelable でない実装がある)。
    // 確定したあとに、入らない文字と溢れた桁を落とす
    input.addEventListener('compositionend', () => enforce(input));
    // 打った時点で止められるものは止める。コードページに無い文字と、桁が溢れる入力である。
    // maxlength は文字数しか見ないので、DBCS では桁の溢れを止められない
    input.addEventListener('beforeinput', (event) => {
      if (event.isComposing) {
        return;
      }
      const inserted = event.data != null
        ? event.data
        : (event.dataTransfer ? event.dataTransfer.getData('text') : '');
      if (!inserted) {
        return;
      }
      const start = input.selectionStart == null ? input.value.length : input.selectionStart;
      const end = input.selectionEnd == null ? start : input.selectionEnd;
      const next = input.value.slice(0, start) + inserted + input.value.slice(end);
      if (!representable(inserted) || cells(next) > Number(input.dataset.length)) {
        event.preventDefault();
      }
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

  // "a6-a8,b0-b1,f7" の形の 16 進の範囲を読む。両端を含む
  function parseRanges(text) {
    if (!text) {
      return [];
    }
    return text.split(',').map((item) => {
      const ends = item.split('-');
      const from = parseInt(ends[0], 16);
      return [from, ends.length > 1 ? parseInt(ends[1], 16) : from];
    });
  }

  function inRanges(ranges, code) {
    let low = 0;
    let high = ranges.length - 1;
    while (low <= high) {
      const middle = (low + high) >> 1;
      if (code < ranges[middle][0]) {
        high = middle - 1;
      } else if (code > ranges[middle][1]) {
        low = middle + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  // その符号位置が占める桁数。0 はコードページに無い (入らない) 文字である。
  // 一覧を読む前は見積りで代える: Latin-1 までと半角カタカナ (U+FF61〜U+FF9F) は混在コードページでも
  // 1 byte であり、それ以外は 2 byte と見る。見積りの間は「入らない文字」を作らない
  function width(code) {
    if (!repertoire) {
      return code > 0xFF && !(code >= 0xFF61 && code <= 0xFF9F) ? 2 : 1;
    }
    if (inRanges(repertoire.single, code)) {
      return 1;
    }
    return inRanges(repertoire.double, code) ? 2 : 0;
  }

  function representable(text) {
    return [...text].every((character) => width(character.codePointAt(0)) > 0);
  }

  // 3270 の桁数。DBCS の 1 文字は 2 桁を占め、混在コードページでは DBCS の連なりを囲む
  // シフトアウトとシフトインも 1 桁ずつ占める。判定は server が同じコードページへ符号化して
  // 行う (ADR-0010)。ここは打ちやすさのためである
  function cells(text) {
    const shifts = repertoire ? repertoire.shifted : true;
    let count = 0;
    let shifted = false;
    for (const character of text) {
      // 入らない文字は桁を数えるうえでは 1 桁と見る。落とすのは representable の側である
      const double = width(character.codePointAt(0)) === 2;
      if (shifts && double !== shifted) {
        count += 1;
        shifted = double;
      }
      count += double ? 2 : 1;
    }
    return shifts && shifted ? count + 1 : count;
  }

  // 入らない文字と溢れた桁を値から落とす。beforeinput で止められなかったぶんの受け皿である
  function enforce(input) {
    const limit = Number(input.dataset.length);
    const caret = input.selectionStart;
    let characters = [...input.value].filter((character) => width(character.codePointAt(0)) > 0);
    while (characters.length > 0 && cells(characters.join('')) > limit) {
      characters.pop();
    }
    const value = characters.join('');
    if (value !== input.value) {
      input.value = value;
      const position = Math.min(caret == null ? value.length : caret, value.length);
      input.setSelectionRange(position, position);
    }
  }

  function prepare(aid) {
    // cursor 位置を画面先頭からの offset で送る
    const cursor = form.querySelector('input[name="cursor"]');
    const active = document.activeElement;
    if (inputs.includes(active)) {
      const row = Number(active.dataset.row) - 1;
      // cursor は画面の桁で送る。caret の位置は文字数なので、桁数に直す
      const column = Number(active.dataset.column) - 1 + cells(active.value.slice(0, active.selectionStart || 0));
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
