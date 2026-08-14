#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IME のストローク割当て（UniStrokeIME.onSymbol）のシーケンステスト。

UniStrokeIME.kt の状態機械を Python に写して、ストロークの並びに対する
確定文字列・合成内容・状態チップを検証する。
Kotlin 側に同じ配線が入っているかも静的に確認する。
"""

from __future__ import annotations

import re
import sys

from unistroke_model import SRC, TPL, Romaji

FAILURES = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def eq(got, want, msg):
    check(got == want, "%s  (got %r)" % (msg, got) if got != want else msg)


# かなモードの半角->全角マッピング（Kotlin: emitSymbol の when）を実装から読む。
# ここを手で二重管理すると、片方だけ足したときにテストが素通りしてしまう。
KANA_SYMBOL_MAP = dict(re.findall(
    r'"(.)" -> "(.)"',
    re.search(r"val kanaMode = .*?\n        \} else \{", SRC["UniStrokeIME"], re.S).group(0)))

KANA_COMPOSING_SYMBOLS = re.search(
    r'KANA_COMPOSING_SYMBOLS\s*=\s*"([^"]+)"', SRC["UniStrokeIME"]).group(1)

AUTO_LATIN_CHIP = re.search(
    r'AUTO_LATIN_CHIP\s*=\s*"([^"]+)"', SRC["UniStrokeIME"]).group(1)

S = TPL.symbols
SPACE, BACKSPACE, RETURN = S["SPACE"], S["BACKSPACE"], S["RETURN"]
SHIFT, EXT, EXT_ALT, TAB, TAP = S["SHIFT"], S["EXT_SHIFT"], S["EXT_SHIFT_ALT"], S["TAB"], S["TAP"]
UNRECOGNIZED = "#none"


class IME:
    """UniStrokeIME の入力ディスパッチ部分の写し（通信・候補バーは除く）。"""

    def __init__(self, mode="HIRAGANA"):
        self.mode = mode          # LATIN / HIRAGANA / KATAKANA
        self.shift = "OFF"        # OFF / ONCE / LOCK
        self.temp_latin = "NONE"  # NONE / UPPER / LOWER
        self.symbol_mode = "NORMAL"
        self.romaji = ""
        self.kana = ""
        self.out = ""             # コミット済み文字列
        self.word_raw = ""        # いまの単語の生ローマ字
        self.auto_latin = False   # 自動アルファベット化中か
        self.last_commit = None   # (確定文字列, 戻す先の読み) / None
        self.selection = None     # (開始, 終了) の文字位置 / None
        self.own_selection = None # そのうち「こちらが選んだ」範囲

    # ---- 表示
    @property
    def katakana(self):
        return self.mode == "KATAKANA"

    def reading_for_conversion(self):
        """変換・予測に使う読み。表示上かなになっている未確定分も含める。"""
        settled = Romaji.settled_pending(self.romaji)
        if self.katakana:
            settled = Romaji.to_katakana(settled)
        return self.kana + settled

    def pending_is_settled(self):
        return Romaji.settled_pending(self.romaji) != ""

    def commit_candidate(self, text, reading=None):
        """候補バーの候補をタップして全文確定する（commitLiveCandidate 相当）。

        候補を選んだ時点でその語は完結しているので、**未確定ローマ字は捨てる**。
        "nn"（すでに「ん」として見えている分）は読みに含まれて確定済み、
        "k" / "ky"（まだかなになっていない子音）は候補に入っていないので、
        どちらも持ち越さないのが正しい。
        """
        self.kana = ""
        self.romaji = ""
        self._end_word()
        self._commit_final(text, reading or text)

    def _commit_final(self, text, undo_reading=None):
        """Kotlin の commitFinal。アプリへ確定する唯一の経路。

        確定するたびに last_commit を必ず書き直す。undo_reading を渡さない確定は
        **直前の記録を捨てる**（記録とアプリ側の実体がずれないようにするため）。
        これが無いと「かなを確定 -> さらに記号を 1 文字確定」の並びで
        last_commit が 1 文字ぶんずれ、次のバックスペースで文字が増える。
        """
        self.out += text
        if undo_reading:
            self._note_commit(text, undo_reading)
        else:
            self._clear_undo()

    def composing(self):
        if self.auto_latin:
            return self.word_raw
        # 「ん」として見せるのは "nn" だけ。単独の "n" は "n" のまま。
        preview = Romaji.preview(self.romaji)
        if self.katakana and preview == "ん":
            preview = "ン"
        return self.kana + preview

    def status(self):
        s = ""
        if self.mode == "LATIN":
            s = {"ONCE": "⇧", "LOCK": "⇧⇧", "OFF": ""}[self.shift]
        else:
            s = {"UPPER": "A", "LOWER": "a", "NONE": ""}[self.temp_latin]
        chip = {"PUNCTUATION": "•", "EXTENDED": "＼", "NORMAL": ""}[self.symbol_mode]
        if chip:
            s = (s + " " + chip) if s else chip
        if self.auto_latin:
            s = (s + " " + AUTO_LATIN_CHIP) if s else AUTO_LATIN_CHIP
        return s

    def mode_label(self):
        if self.mode == "HIRAGANA":
            return "かな"
        if self.mode == "KATAKANA":
            return "カナ"
        return {"OFF": "abc", "ONCE": "Abc", "LOCK": "ABC"}[self.shift]

    # ---- 内部
    def _merge_pending(self):
        if self.romaji:
            self.kana += Romaji.flush(self.romaji) if not self.katakana else \
                Romaji.to_katakana(Romaji.flush(self.romaji))
            self.romaji = ""

    def _note_commit(self, surface, reading):
        self.last_commit = (surface, reading) if surface and reading else None

    def _clear_undo(self):
        self.last_commit = None
        self.own_selection = None

    def _delete_own_selection(self):
        """こちらが選んだ範囲（全体 or 長押しで選んだ範囲）なら消して True。"""
        sel = self.selection
        if sel is None or sel[1] <= sel[0]:
            return False
        whole = sel[0] == 0 and sel[1] >= len(self.out)
        mine = self.own_selection == sel
        if not whole and not mine:
            return False
        self.out = self.out[:sel[0]] + self.out[sel[1]:]
        self.selection = None
        self.own_selection = None
        return True

    def select_all(self):
        """全選択ボタンのタップ。すでに選択済みなら削除。"""
        self._flush()
        deleted = self._delete_own_selection()
        self._clear_undo()
        if deleted:
            return "deleted"
        self.selection = (0, len(self.out))
        return "selected"

    def select_last_commit(self):
        """全選択ボタンの長押し。直前確定が生きていればそこだけ選ぶ。"""
        if self._delete_own_selection():
            self._clear_undo()
            return "deleted"
        undo = self.last_commit
        if undo is not None and not self.kana and not self.romaji \
                and self.out.endswith(undo[0]):
            self.selection = (len(self.out) - len(undo[0]), len(self.out))
            self.own_selection = self.selection
            self.last_commit = None
            return True
        self.select_all()
        return False

    def move_cursor(self):
        self._flush()
        self._clear_undo()
        self.selection = None

    def _undo_commit(self):
        """直前の確定を取り消して、確定前のひらがな合成へ戻す。"""
        if self.last_commit is None:
            return False
        surface, reading = self.last_commit
        self.last_commit = None
        # 消す前に、カーソル直前が本当にその文字列か確かめる（Kotlin 側も
        # undoLastCommit で getTextBeforeCursor による同じ確認をしている）。
        # False を返すと呼び出し側は通常のバックスペースへ落ちる。
        if not self.out.endswith(surface):
            return False
        self.out = self.out[:-len(surface)]
        self.kana = reading
        self.romaji = ""
        self._end_word()
        return True

    def _end_word(self):
        self.word_raw = ""
        self.auto_latin = False

    def _revert_auto_latin(self):
        """かな解釈へ戻す（候補バーの「かな解釈」タップ相当）。"""
        if not self.auto_latin:
            return
        self.auto_latin = False
        kana, pending = Romaji.convert(self.word_raw)
        self.kana = Romaji.to_katakana(kana) if self.katakana else kana
        self.romaji = pending

    def candidates(self):
        """自動英字化中に候補バーへ出る 2 件。"""
        if not self.auto_latin or not self.word_raw:
            return []
        raw = self.word_raw
        kana = Romaji.flush(raw)
        out = [raw]
        if kana and kana != raw:
            out.append(kana)
        return out

    def _flush(self):
        if self.auto_latin:
            raw = self.word_raw
            self._end_word()
            self.kana = ""
            self.romaji = ""
            # 生の綴りの確定は戻す先（読み）が無いので undo にしない
            self._commit_final(raw)
            return
        if not self.kana and not self.romaji:
            self._end_word()
            return
        self._merge_pending()
        text = self.kana
        self.kana = ""
        self._end_word()
        self._commit_final(text, text)

    # ---- 公開: 1 ストローク
    def stroke(self, sym):
        # 確定アンドゥが効くのは「確定直後のバックスペース」だけ
        if sym != BACKSPACE:
            self._clear_undo()
        if sym == UNRECOGNIZED:
            if self.symbol_mode != "NORMAL":
                self.symbol_mode = "NORMAL"
            return
        if sym == TAP:
            if self.symbol_mode == "NORMAL":
                self.symbol_mode = "PUNCTUATION"
            elif self.symbol_mode == "PUNCTUATION":
                self.symbol_mode = "NORMAL"
                self._emit(".")
            else:
                self.symbol_mode = "NORMAL"
                self._emit("•", extended=True)
            return
        if self.symbol_mode != "NORMAL":
            mode = self.symbol_mode
            self.symbol_mode = "NORMAL"
            if sym == BACKSPACE:
                return
            if sym == TAB:
                self._flush()
                self._commit_final("\t")
                return
            self._emit(sym, extended=(mode == "EXTENDED"))
            return

        if sym == SHIFT:
            self._shift_stroke()
        elif sym == EXT:
            self.symbol_mode = "EXTENDED"
        elif sym == EXT_ALT:
            if self.mode == "LATIN":
                self.symbol_mode = "EXTENDED"
            else:
                self._kana_script_toggle()
        elif sym == SPACE:
            self._space()
        elif sym == BACKSPACE:
            self._backspace()
        elif sym == RETURN:
            self._return()
        elif sym == TAB:
            self._flush()
            self._commit_final("\t")
        else:
            self._character(sym)

    def _emit(self, sym, extended=False):
        # 自動英字化中は ASCII 扱いなので半角のまま
        kana_mode = not extended and self.mode != "LATIN" and not self.auto_latin
        text = KANA_SYMBOL_MAP.get(sym, sym) if kana_mode else sym
        # かなモードの全角記号は確定させず合成へ足す（読みの一部として変換にかける）
        if kana_mode and text in KANA_COMPOSING_SYMBOLS:
            self._merge_pending()
            self.kana += text
            self.word_raw += text
            return
        self._flush()
        self._commit_final(text)

    def _shift_stroke(self):
        if self.mode == "LATIN":
            self.shift = {"OFF": "ONCE", "ONCE": "LOCK", "LOCK": "OFF"}[self.shift]
            return
        self.temp_latin = {"NONE": "UPPER", "UPPER": "LOWER", "LOWER": "NONE"}[self.temp_latin]

    def _kana_script_toggle(self):
        self.temp_latin = "NONE"
        carry = self.romaji
        self.romaji = ""
        self._flush()
        self.mode = "HIRAGANA" if self.mode == "KATAKANA" else "KATAKANA"
        self.romaji = carry

    def _character(self, sym):
        if self.mode == "LATIN":
            self._commit_final(sym if self.shift == "OFF" else sym.upper())
            if self.shift == "ONCE":
                self.shift = "OFF"
            return
        if self.temp_latin != "NONE":
            upper = self.temp_latin == "UPPER"
            self.temp_latin = "NONE"
            self._flush()
            self._commit_final(sym.upper() if upper else sym.lower())
            return
        if len(sym) == 1 and sym.isdigit():
            self._flush()
            self._commit_final(sym)
            return
        self.word_raw += sym
        if self.auto_latin:
            return
        self.romaji += sym
        kana, pending = Romaji.convert(self.romaji)
        self.kana += Romaji.to_katakana(kana) if self.katakana else kana
        self.romaji = pending
        # 日本語のローマ字として無理があればこの単語だけ英字へ倒す（ひらがなのみ）
        if self.mode == "HIRAGANA" and Romaji.looks_non_japanese(self.word_raw):
            self.auto_latin = True
            self.kana = ""
            self.romaji = ""

    def _return(self):
        """リターン。合成中は確定のみ、合成が空なら改行。"""
        if self.auto_latin:
            self._flush()
            return
        if self.kana or self.romaji:
            self._flush()
            return
        self._flush()
        self.out += "\n"
        # 改行は sendKeyEvent で送るので確定として記録できない。
        # 記録が残っていると次のバックスペースで実体とずれる。
        self._clear_undo()

    def _space(self):
        if self.mode == "HIRAGANA" and self.reading_for_conversion():
            self._merge_pending()
            return  # 変換要求（オフラインでは合成のまま）
        self._flush()
        self._commit_final(" ")

    def _backspace(self):
        # 確定直後の 1 回目は確定を取り消して確定前へ戻す
        if not self.kana and not self.romaji and not self.auto_latin \
                and self.last_commit is not None:
            if self._undo_commit():
                return
        if self.auto_latin:
            self.word_raw = self.word_raw[:-1]
            if not self.word_raw or not Romaji.looks_non_japanese(self.word_raw):
                self._revert_auto_latin()
            return
        if self.romaji:
            self.romaji = self.romaji[:-1]
            self.word_raw = self.word_raw[:-1]
            return
        if self.kana:
            self.kana = self.kana[:-1]
            self._end_word()
            return
        self.out = self.out[:-1]


# to_katakana を Romaji に生やす（Kotlin と同じ変換）
Romaji.to_katakana = staticmethod(
    lambda s: "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)
)


class InputConnectionMock:
    """Android の InputConnection の意味論をそのまま写したモック。

    合成領域まわりのバグ（挿入位置の間違い・合成の確定漏れ）は、
    composing の中身だけを見ていると素通りしてしまう。
    確定済みテキストと合成テキストの**両方**を保持して、
    画面に見えている文字列全体を検証できるようにする。

      setComposingText(t)     合成領域があれば置換、無ければ**選択範囲**を置換
                              （選択が無ければカーソル位置へ挿入）
      finishComposingText()   合成領域を解除（中身は文書に残る = 確定される）
      commitText(t)           合成領域（無ければ選択範囲）を t で置換し合成を解除
      getTextBeforeCursor(n)  カーソル（選択の開始）直前の n 文字
      getSelectedText()       選択が無ければ None
      deleteSurroundingText   カーソル直前 n 文字を削除

    選択範囲を持っているのが重要な点。setComposingText("") を合成領域が無い
    状態で送ると **選択中のテキストが消える**ので、そのバグを再現できる。
    """

    def __init__(self, text="", name="ic"):
        self.text = text
        self.sel = (len(text), len(text))   # (開始, 終了)。等しければキャレット
        self.comp = None
        self.name = name

    # --- 内部
    def _replace(self, start, end, t):
        self.text = self.text[:start] + t + self.text[end:]
        pos = start + len(t)
        self.sel = (pos, pos)

    def _target(self):
        """書き込み先。合成領域があればそこ、無ければ選択範囲（= キャレット）。"""
        return self.comp if self.comp is not None else self.sel

    @property
    def cursor(self):
        return self.sel[1]

    # --- InputConnection API
    def set_composing_text(self, t):
        s, e = self._target()
        self._replace(s, e, t)
        self.comp = (s, s + len(t))

    def finish_composing_text(self):
        self.comp = None

    def commit_text(self, t):
        s, e = self._target()
        self._replace(s, e, t)
        self.comp = None

    def delete_surrounding_text(self, before, after=0):
        s = max(0, self.sel[0] - before)
        e = min(len(self.text), self.sel[1] + after)
        if self.comp is not None:
            # 合成領域が削除範囲に食い込んだら、その分だけ縮める（実機に合わせる）
            cs, ce = self.comp
            cs = cs - max(0, min(ce, e) - max(cs, s)) if cs > s else cs
            self.comp = None
        self._replace(s, e, "")
        return True

    def get_text_before_cursor(self, n, flags=0):
        s = self.sel[0]
        return self.text[max(0, s - n):s]

    def get_selected_text(self, flags=0):
        s, e = self.sel
        return self.text[s:e] if e > s else None

    def set_selection(self, s, e):
        self.sel = (s, e)
        return True

    # --- 検査用
    def committed(self):
        if self.comp is None:
            return self.text
        s, e = self.comp
        return self.text[:s] + self.text[e:]

    def composing(self):
        if self.comp is None:
            return ""
        s, e = self.comp
        return self.text[s:e]

    def whole(self):
        """画面に見えている文字列すべて（確定 + 合成）。"""
        return self.text


class ComposingIME:
    """UniStrokeIME の合成領域・確定まわりを、InputConnection の呼び出し順ごと写したもの。

    [IME] は状態遷移の検証用で InputConnection を持たない。
    こちらは「どういう順で ic を叩くか」を検証するためのもので、
    合成の確定漏れ・挿入位置のバグ・確定アンドゥの消しすぎを捕まえる。

    Kotlin 側の不変条件をそのまま写している:
      ・合成領域を触るのは _clear_composing / _show_composing だけ
      ・アプリへ確定するのは _commit_final だけ（毎回 last_commit を書き直す）
      ・composing_shown は「どの ic に対して出しているか」まで覚える
    """

    def __init__(self, ic=None):
        self.ic = ic if ic is not None else InputConnectionMock()
        self.kana = ""
        self.romaji = ""
        self.composing_shown = False     # Kotlin の composingShown
        self.composing_owner = None      # Kotlin の composingOwner
        self.segments = []               # 変換中の文節（候補リストの列）
        self.choices = []
        self.last_commit = None
        self.mode = "HIRAGANA"

    # --- Kotlin: currentInputConnection（None は「接続が無い」）
    def _cur(self):
        return self.ic

    # --- Kotlin: 合成領域の記録
    def _composing_shown_on(self, ic):
        return self.composing_shown and self.composing_owner is ic

    def _note_composing_gone(self):
        self.composing_shown = False
        self.composing_owner = None

    def _clear_composing(self, ic):
        if not self._composing_shown_on(ic):
            # 出していない、または別の入力欄に対して出した記録。何も送らない。
            self._note_composing_gone()
            return
        if ic.get_selected_text():
            # 選択があるなら合成領域は存在しないはず。setComposingText("") は
            # 選択を消してしまうので、記録の方がずれていると判断して送らない。
            self._note_composing_gone()
            return
        ic.set_composing_text("")
        self._note_composing_gone()
        ic.finish_composing_text()

    def _show_composing(self, ic, text):
        if text == "":
            self._clear_composing(ic)
            return
        ic.set_composing_text(text)
        self.composing_shown = True
        self.composing_owner = ic

    def _update_composing(self):
        ic = self._cur()
        if ic is None:
            return
        self._show_composing(ic, self.kana + Romaji.preview(self.romaji))

    def _update_conversion_composing(self):
        ic = self._cur()
        if ic is None:
            return
        self._show_composing(ic, self._conversion_text())

    def _conversion_text(self):
        return "".join(self.segments[i][self.choices[i]] for i in range(len(self.segments)))

    def _composing_length(self):
        return len(self.kana) + len(self.romaji)

    # --- Kotlin: commitFinal（アプリへ確定する唯一の経路）
    def _commit_final(self, ic, text, undo_reading=None):
        ic.commit_text(text)
        self._note_composing_gone()
        if undo_reading:
            self.last_commit = (text, undo_reading) if text else None
        else:
            self.last_commit = None

    def _clear_undo(self):
        self.last_commit = None

    # --- Kotlin: flushComposing
    def _flush(self):
        ic = self._cur()
        if ic is None:
            return
        if self._composing_length() == 0:
            self._clear_composing(ic)
            return
        if self.romaji:
            self.kana += Romaji.flush(self.romaji)
            self.romaji = ""
        text = self.kana
        self.kana = ""
        self._commit_final(ic, text, text)

    # --- Kotlin: finishConversionIfAny
    def _finish_conversion(self):
        if not self.segments:
            return
        text = self._conversion_text()
        reading = self.kana
        self.segments = []
        self.choices = []
        self.kana = ""
        self.romaji = ""
        ic = self._cur()
        if ic is None:
            # 接続が無いのでアプリ側には何も入っていない。記録も残さない。
            self._note_composing_gone()
            self._clear_undo()
            return
        self._commit_final(ic, text, reading)

    # ------------------------------------------------------------ 操作
    def type(self, spell):
        for ch in spell:
            self.romaji += ch
            kana, pending = Romaji.convert(self.romaji)
            self.kana += kana
            self.romaji = pending
            self._update_composing()
        return self

    def commit_candidate(self, text, reading=None):
        """候補バーの候補をタップして確定する（Kotlin: commitLiveCandidate）。

        候補を選んだ時点で語は完結しているので、未確定ローマ字は捨てる。
        """
        self.kana = ""
        self.romaji = ""
        self.ic.commit_text(text)
        self.composing_shown = False
        self.last_commit = (text, reading or text)
        return self

    def symbol(self, sym):
        """かなモードの記号入力（Punctuation 経由）。全角なら合成へ足す。"""
        text = KANA_SYMBOL_MAP.get(sym, sym)
        if text in KANA_COMPOSING_SYMBOLS:
            if self.romaji:
                self.kana += Romaji.flush(self.romaji)
                self.romaji = ""
            self.kana += text
            self._update_composing()
            return self
        # 半角記号は確定して挿入
        self.confirm()
        self.ic.commit_text(text)
        self.composing_shown = False
        return self

    def convert(self, candidates):
        """スペース相当。[候補リスト] を 1 文節として変換モードへ入る。"""
        if self.romaji:
            self.kana += Romaji.flush(self.romaji)
            self.romaji = ""
        self._update_composing()
        self.segments = [list(candidates)]
        self.choices = [0]
        self._update_conversion_composing()
        return self

    def cycle(self):
        """スペースで候補を送る。"""
        self.choices[0] = (self.choices[0] + 1) % len(self.segments[0])
        self._update_conversion_composing()
        return self

    def confirm(self):
        """リターン相当。変換中なら全文節確定、合成中ならかな確定。"""
        if self.segments:
            self._finish_conversion()
            return self
        self._flush()
        return self

    def emit_symbol(self, symbol):
        """記号ストローク（Kotlin: emitSymbol）。

        finishConversionIfAny -> flushComposing -> 記号を 1 文字コミット。
        「確定したあとにもう 1 文字コミットする」経路の代表。
        """
        ic = self._cur()
        text = KANA_SYMBOL_MAP.get(symbol, symbol) if self.mode != "LATIN" else symbol
        # かなモードの全角記号は確定させず合成へ足す
        if self.mode != "LATIN" and text in KANA_COMPOSING_SYMBOLS:
            if self.romaji:
                self.kana += Romaji.flush(self.romaji)
                self.romaji = ""
            self.kana += text
            self._update_composing()
            return self
        self._finish_conversion()
        self._flush()
        if ic is None:
            return self
        self._commit_final(ic, text)
        return self

    def space(self):
        """かな以外／読みが無いときのスペース（Kotlin: onSpace の後半）。"""
        ic = self._cur()
        self._flush()
        if ic is None:
            return self
        self._commit_final(ic, " ")
        return self

    def digit(self, d):
        """数字ゾーンの数字（Kotlin: onCharacter の数字分岐）。"""
        ic = self._cur()
        self._flush()
        if ic is None:
            return self
        self._commit_final(ic, d)
        return self

    def tab(self):
        ic = self._cur()
        self._flush()
        if ic is None:
            return self
        self._commit_final(ic, "\t")
        return self

    def unrecognized(self):
        """認識できなかったストローク（Kotlin: UNRECOGNIZED 分岐）。

        合成領域にも確定済みテキストにも触らない。確定アンドゥの記録だけ捨てる
        （onSymbol 冒頭の clearUndo）。
        """
        self._clear_undo()
        return self

    def other_stroke(self):
        """バックスペース以外のストロークが 1 つ挟まった（確定アンドゥは無効になる）。"""
        self._clear_undo()
        return self

    def backspace(self):
        ic = self._cur()
        if ic is None:
            return self
        if self.segments:
            # 変換キャンセル -> ひらがな合成へ戻す
            self.segments = []
            self.choices = []
            self._update_composing()
            return self
        if self._composing_length() == 0 and self.last_commit is not None:
            surface, reading = self.last_commit
            self.last_commit = None
            self._clear_composing(ic)
            # 消す前に、直前が本当にその文字列か確かめる（Kotlin: undoLastCommit）
            if ic.get_text_before_cursor(len(surface)) == surface:
                ic.delete_surrounding_text(len(surface))
                self.kana = reading
                self.romaji = ""
                self._update_composing()
                return self
            # ずれていた -> 通常のバックスペースへ落ちる
        if self.romaji:
            self.romaji = self.romaji[:-1]
            self._update_composing()
            return self
        if self.kana:
            self.kana = self.kana[:-1]
            self._update_composing()
            return self
        selected = ic.get_selected_text()
        if selected:
            self._commit_final(ic, "")
        else:
            ic.delete_surrounding_text(1)
        return self

    # --- 入力欄まわり（Kotlin: onStartInput / onUnbindInput / onFinishInputView）
    def switch_field(self, ic=None):
        """別の入力欄へ切り替わる（InputConnection が別インスタンスになる）。

        Kotlin: onFinishInputView で合成を確定 -> onStartInput で記録を捨てる。
        """
        self._flush()
        self.ic = ic if ic is not None else InputConnectionMock(name="ic2")
        self._start_input()
        return self

    def _start_input(self):
        self._note_composing_gone()
        self._clear_undo()
        self.kana = ""
        self.romaji = ""
        self.segments = []
        self.choices = []

    def unbind(self):
        """接続が外れる（currentInputConnection が None になる）。"""
        self._note_composing_gone()
        self._clear_undo()
        self.ic = None
        return self

    def rebind(self, ic):
        """新しい接続が来る。"""
        self.ic = ic
        self._start_input()
        return self


def typed(ime, text):
    for ch in text:
        ime.stroke(ch)
    return ime


def main():
    print("=== 1. かなモード: 右上スワイプ（／）= ひらがな ⇄ カタカナ ===")
    ime = IME()
    typed(ime, "ka")
    eq(ime.composing(), "か", "ka -> か")
    ime.stroke(EXT_ALT)
    eq(ime.mode, "KATAKANA", "／ で カタカナへ")
    eq(ime.out, "か", "切替時に合成中のかなは確定される")
    eq(ime.symbol_mode, "NORMAL", "／ で Extended には入らない")
    typed(ime, "na")
    eq(ime.composing(), "ナ", "カタカナで入力される")
    ime.stroke(EXT_ALT)
    eq(ime.mode, "HIRAGANA", "／ をもう一度で ひらがなへ戻る")
    eq(ime.mode_label(), "かな", "モードラベルが かな")

    print("\n=== 2. 音節の途中で切り替えても未確定ローマ字を持ち越す ===")
    ime = IME()
    ime.stroke("k")
    eq(ime.romaji, "k", "k が未確定")
    ime.stroke(EXT_ALT)
    eq(ime.romaji, "k", "切替後も k を持ち越す")
    eq(ime.mode, "KATAKANA", "カタカナへ")
    ime.stroke("a")
    eq(ime.composing(), "カ", "k + a -> カ")

    print("\n=== 3. かなモード: 上スワイプ = 一時アルファベット入力 ===")
    ime = IME()
    ime.stroke(SHIFT)
    eq(ime.temp_latin, "UPPER", "1 回で大文字待ち")
    eq(ime.status(), "A", "状態チップは A")
    ime.stroke("g")
    eq(ime.out, "G", "次の 1 文字が大文字 Latin で確定")
    eq(ime.temp_latin, "NONE", "1 文字入れたら自動でかなへ復帰")
    eq(ime.status(), "", "チップが消える")
    typed(ime, "ka")
    eq(ime.composing(), "か", "そのままかな入力へ戻る")

    ime = IME()
    ime.stroke(SHIFT)
    ime.stroke(SHIFT)
    eq(ime.temp_latin, "LOWER", "2 回で小文字待ち")
    eq(ime.status(), "a", "状態チップは a")
    ime.stroke("g")
    eq(ime.out, "g", "次の 1 文字が小文字 Latin で確定")
    eq(ime.temp_latin, "NONE", "自動復帰")

    ime = IME()
    for _ in range(3):
        ime.stroke(SHIFT)
    eq(ime.temp_latin, "NONE", "3 回で解除")
    eq(ime.status(), "", "チップなし")

    print("\n=== 4. 一時アルファベットは合成中のかなを確定してから入る ===")
    ime = IME()
    typed(ime, "ka")
    ime.stroke(SHIFT)
    ime.stroke("b")
    eq(ime.out, "かB", "か を確定してから B")
    eq(ime.composing(), "", "合成は空")

    print("\n=== 5. かなモードの Extended は「＼」だけ ===")
    ime = IME()
    ime.stroke(EXT)
    eq(ime.symbol_mode, "EXTENDED", "＼ で Extended")
    eq(ime.status(), "＼", "チップは ＼")
    ime.stroke("°")
    eq(ime.out, "°", "次の 1 ストロークが特殊文字")
    eq(ime.symbol_mode, "NORMAL", "1 ストロークで自動解除")

    print("\n=== 6. abc モードは従来どおり ===")
    ime = IME("LATIN")
    ime.stroke(SHIFT)
    eq(ime.shift, "ONCE", "abc: ↑ 1 回で Abc")
    eq(ime.mode_label(), "Abc", "ラベル Abc")
    ime.stroke("a")
    eq(ime.out, "A", "次の 1 文字だけ大文字")
    eq(ime.shift, "OFF", "自動解除")
    ime.stroke(SHIFT)
    ime.stroke(SHIFT)
    eq(ime.shift, "LOCK", "2 回で CapsLock")
    ime.stroke(SHIFT)
    eq(ime.shift, "OFF", "3 回で解除")
    ime2 = IME("LATIN")
    ime2.stroke(EXT_ALT)
    eq(ime2.symbol_mode, "EXTENDED", "abc: ／ でも Extended に入れる")
    ime3 = IME("LATIN")
    ime3.stroke(EXT)
    eq(ime3.symbol_mode, "EXTENDED", "abc: ＼ でも Extended")

    print("\n=== 7. かなの通し入力 ===")
    ime = IME()
    typed(ime, "konnichiha")
    eq(ime.composing(), "こんにちは", "konnichiha -> こんにちは")
    ime = IME()
    typed(ime, "kyou")
    eq(ime.composing(), "きょう", "kyou -> きょう")
    ime = IME()
    typed(ime, "gakkou")
    eq(ime.composing(), "がっこう", "gakkou -> がっこう")
    ime = IME()
    typed(ime, "ka")
    ime.stroke(BACKSPACE)
    eq(ime.composing(), "", "バックスペースでかなが消える")

    print("\n=== 7b. 「ん」は nn のみ・単独 n は n 表示 ===")
    ime = IME()
    seen = []
    for ch in "kondo":
        ime.stroke(ch)
        seen.append(ime.composing())
    eq(seen, ["k", "こ", "こn", "こんd", "こんど"],
       "kondo の表示遷移（n は子音が来るまで n のまま）")
    ime = IME()
    typed(ime, "kan")
    eq(ime.composing(), "かn", "kan は「かn」と見える（「かん」に見せない）")
    ime.stroke(SPACE)
    eq(ime.kana, "かn", "スペース（変換要求）でも末尾 n は Latin の n のまま")
    ime = IME()
    typed(ime, "kann")
    eq(ime.composing(), "かん", "kann で「かん」")
    ime = IME()
    typed(ime, "kan")
    ime.stroke(RETURN)
    eq(ime.out, "かn", "リターン確定でも末尾 n は n（確定のみ・改行なし）")
    ime = IME()
    typed(ime, "kan")
    ime.stroke(EXT_ALT)
    eq(ime.mode, "KATAKANA", "カナ切替")
    eq(ime.out + ime.composing(), "かn", "切替時の確定でも末尾 n は n（持ち越しは無し）")
    ime = IME()
    typed(ime, "nn")
    eq(ime.composing(), "ん", "かなモードで nn -> ん")
    ime = IME("KATAKANA")
    typed(ime, "nn")
    eq(ime.composing(), "ン", "カタカナモードで nn -> ン")
    ime = IME("KATAKANA")
    ime.stroke("n")
    eq(ime.composing(), "n", "カタカナモードでも n 単独は n 表示")
    ime = IME()
    ime.stroke("n")
    eq(ime.composing(), "n", "n 単独は n 表示")
    ime.stroke(BACKSPACE)
    eq(ime.composing(), "", "その n はバックスペース 1 回で消える")

    print("\n=== 8. Kotlin 側の配線 ===")
    ime_src = SRC["UniStrokeIME"]
    view_src = SRC["UniStrokeView"]
    tpl_src = SRC["StrokeTemplates"]
    check("EXT_SHIFT_ALT" in tpl_src and 't(EXT_SHIFT_ALT' in tpl_src,
          "「／」テンプレートが EXT_SHIFT_ALT になっている")
    check(tpl_src.count("t(EXT_SHIFT,") == 1, "EXT_SHIFT（＼）のテンプレートは 1 つだけ")
    check("StrokeTemplates.EXT_SHIFT_ALT ->" in ime_src, "IME が EXT_SHIFT_ALT を分岐している")
    check(re.search(r"EXT_SHIFT_ALT ->\s*\n\s*if \(inputMode == InputMode\.LATIN\)", ime_src)
          is not None, "／ は abc なら Extended、かなならカナ切替")
    check("onKanaScriptToggle" in ime_src, "かな用のスクリプト切替が独立している")
    check("TempLatin" in ime_src and "TempLatin.UPPER" in ime_src and "TempLatin.LOWER" in ime_src,
          "一時アルファベット入力の状態を持っている")
    check('TEMP_LATIN_UPPER = "A"' in ime_src and 'TEMP_LATIN_LOWER = "a"' in ime_src,
          "状態チップが A / a")
    check("override fun expectedSymbols()" in ime_src, "文脈バイアスの供給を実装している")
    check("fun expectedSymbols(): Set<String> = emptySet()" in view_src,
          "UniStrokeView.Listener に既定実装がある")
    check("StrokeRecognizer.CONTEXT_BONUS" in view_src, "認識時に文脈バイアスを渡している")
    check("EXT_SHIFT_ALT -> \"カナ/ext\"" in view_src, "見本オーバーレイのラベルがある")
    # かなモードで tempLatin をクリアする箇所
    for where in ("resetAll", "onModeToggle"):
        idx = ime_src.find("fun %s(" % where)
        check(idx > 0 and "tempLatin = TempLatin.NONE" in ime_src[idx:idx + 1400],
              "%s() で一時アルファベットを解除している" % where)

    print("\n=== 自動アルファベット化（単語単位） ===")
    ime = IME()
    typed(ime, "st")
    eq(ime.auto_latin, False, "st ではまだかな解釈のまま")
    eq(ime.composing(), "st", "st の composing はかな解釈のまま（回復処理 1 回）")
    ime.stroke("r")
    eq(ime.auto_latin, True, "str で自動アルファベット化が発動する")
    eq(ime.composing(), "str", "発動後の composing は打った綴りそのまま")
    eq(ime.out, "", "発動しても何も確定しない（composing のまま）")
    eq(AUTO_LATIN_CHIP in ime.status(), True, "状態チップに %s が出る" % AUTO_LATIN_CHIP)
    typed(ime, "ike")
    eq(ime.composing(), "strike", "続きも綴りのまま伸びる")
    eq(ime.candidates(), ["strike", Romaji.flush("strike")],
       "候補バーに [綴り, かな解釈] の 2 件が出る")

    # スペースで確定すると綴りがそのまま出る
    ime.stroke(SPACE)
    eq(ime.out, "strike ", "スペースで綴りがそのまま確定する")
    eq(ime.auto_latin, False, "確定で自動英字化は解除される")
    eq(ime.word_raw, "", "単語の生履歴もクリアされる")

    print("\n=== 誤判定の逃げ道: かな解釈へ戻せる ===")
    ime = IME()
    typed(ime, "night")
    eq(ime.auto_latin, True, "night で発動")
    eq(ime.composing(), "night", "発動中は綴りそのまま")
    ime._revert_auto_latin()
    eq(ime.auto_latin, False, "かな解釈タップで解除される")
    eq(ime.out, "", "戻すときに確定はしない")
    eq(ime.composing(), "にght", "composing がかな解釈（にght）へ戻る")

    print("\n=== バックスペースで自動英字化が解ける ===")
    ime = IME()
    typed(ime, "night")
    eq(ime.auto_latin, True, "night で発動")
    ime.stroke(BACKSPACE)
    eq(ime.auto_latin, False, "1 文字消すと発動条件（回復 2 回）を割ってかな解釈へ戻る")
    eq(ime.composing(), "にgh", "composing が nigh のかな解釈へ戻る")

    print("\n=== 日本語入力は一切影響を受けない ===")
    for word, want in [("kyou", "きょう"), ("konnichiha", "こんにちは"),
                       ("gakkou", "がっこう"), ("kansha", "かんしゃ"),
                       ("issho", "いっしょ"), ("kta", "kた")]:
        ime = IME()
        typed(ime, word)
        eq(ime.auto_latin, False, "%r で自動英字化しない" % word)
        eq(ime.composing(), want, "%r -> %s" % (word, want))

    print("\n=== 単語をまたいで持ち越さない ===")
    ime = IME()
    typed(ime, "str")
    ime.stroke(SPACE)
    typed(ime, "kyou")
    eq(ime.auto_latin, False, "前の単語の判定を次の単語へ持ち越さない")
    eq(ime.composing(), "きょう", "次の単語は普通にかな入力できる")

    print("\n=== カタカナモードでは発動しない（意図的な選択なので触らない）===")
    ime = IME(mode="KATAKANA")
    typed(ime, "strike")
    eq(ime.auto_latin, False, "カタカナモードでは自動英字化しない")

    print("\n=== 確定アンドゥ（バックスペースで確定前へ戻す）===")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(SPACE)          # かな確定（オフラインでは変換要求だが合成のまま）
    ime.stroke(RETURN)         # ここで確定（改行はしない）
    eq(ime.out, "きょう", "リターンで確定される（改行は入らない）")
    # リターン後は改行が入るので、確定アンドゥの対象は改行前の確定
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    eq(ime.out, "きょう", "確定された")
    eq(ime.last_commit, ("きょう", "きょう"), "確定内容が記録される")
    ime.stroke(BACKSPACE)
    eq(ime.out, "", "1 回目のバックスペースで確定が取り消される")
    eq(ime.composing(), "きょう", "確定前のひらがな合成に戻る")
    ime.stroke(BACKSPACE)
    eq(ime.composing(), "きょ", "2 回目からは 1 文字ずつ消える")
    ime.stroke(BACKSPACE)
    eq(ime.composing(), "き", "3 回目も 1 文字ずつ")

    print("\n=== 確定後に別の入力が挟まったら通常動作 ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "ki")           # ストロークが挟まる
    eq(ime.last_commit, None, "他のストロークでアンドゥが無効になる")
    eq(ime.composing(), "き", "挟まったストロークは普通にかなになる")
    ime.stroke(BACKSPACE)
    eq(ime.composing(), "", "通常のバックスペース（合成の末尾から消える）")
    eq(ime.out, "きょう", "確定済みの文字列は残る")

    print("\n=== 自動英字化の確定はアンドゥ対象外（戻す読みが無い）===")
    ime = IME()
    typed(ime, "str")
    ime._flush()
    eq(ime.out, "str", "綴りがそのまま確定される")
    eq(ime.last_commit, None, "自動英字化の確定は記録しない")

    print("\n=== 全選択ボタンの長押し（直前確定を選択）===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "tenki")
    ime._flush()
    eq(ime.out, "きょうてんき", "2 語を確定した")
    got = ime.select_last_commit()
    eq(got, True, "直前確定が生きているので、その範囲を選ぶ")
    eq(ime.selection, (3, 6), "直前に確定した「てんき」だけが選択される")
    eq(ime.out[ime.selection[0]:ime.selection[1]], "てんき", "選択範囲の中身が直前確定と一致")

    print("\n=== カーソル移動を挟んだら全選択へ落ちる ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    ime.move_cursor()
    got = ime.select_last_commit()
    eq(got, False, "直前確定が無効なので全選択へ落ちる")
    eq(ime.selection, (0, len(ime.out)), "全選択になる")

    print("\n=== 他の入力を挟んだ場合も全選択 ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "ki")
    got = ime.select_last_commit()
    eq(got, False, "ストロークが挟まったら全選択")

    print("\n=== 書き方トレーニングの状態機械 ===")
    chars = re.findall(r'"([a-z])"', re.search(
        r"val DEFAULT_CHARS = listOf\(([^)]*)\)", SRC["TrainingSession"]).group(1))
    required = int(re.search(r"REQUIRED_SUCCESSES = (\d+)", SRC["TrainingSession"]).group(1))
    good = float(re.search(r"GOOD_SCORE = ([\d.]+)f", SRC["TrainingSession"]).group(1))
    print("       練習文字: %s（%d 文字）" % (" ".join(chars), len(chars)))
    eq(len(chars), 9, "練習する文字が 9 文字")
    check("b" in chars, "b が練習対象に入っている")
    check(chars.index("b") == chars.index("p") + 1,
          "b は同系統の p の隣に置かれている（%s）" % " ".join(chars))
    check(len(set(chars)) == len(chars), "重複が無い")
    letters = {s for s, _ in TPL.blocks["letters"]}
    check(all(c in letters for c in chars), "すべて実在する字形")

    class Training:
        """TrainingSession.kt の写し。"""

        def __init__(self, chars):
            self.chars = list(chars)
            self.index = 0
            self.successes = 0
            self.learned = []

        @property
        def finished(self):
            return self.index >= len(self.chars)

        @property
        def current(self):
            return self.chars[self.index] if not self.finished else None

        def position(self):
            return min(self.index + 1, len(self.chars))

        def attempt(self, recognized, score, save_ok=True):
            target = self.current
            if target is None:
                return dict(correct=False, learned=False, rejected=False,
                            char_completed=False, finished=True)
            correct = recognized == target
            weak = correct and score < good
            learned = rejected = False
            if not correct or weak:
                if save_ok:
                    learned = True
                    if target not in self.learned:
                        self.learned.append(target)
                else:
                    rejected = True
            if correct:
                self.successes += 1
            done = self.successes >= required
            if done:
                self.index += 1
                self.successes = 0
            return dict(correct=correct, learned=learned, rejected=rejected,
                        char_completed=done, finished=self.finished)

        def skip_current(self):
            if not self.finished:
                self.index += 1
                self.successes = 0

        def skip_all(self):
            self.index = len(self.chars)
            self.successes = 0

    print("       -- 進行: 各文字を %d 回成功で次へ --" % required)
    t = Training(chars)
    eq(t.current, chars[0], "最初は %s" % chars[0])
    eq(t.position(), 1, "進捗は 1 文字目")
    for i, c in enumerate(chars):
        for n in range(required):
            st = t.attempt(c, 0.95)
            eq(st["correct"], True, "%s の %d 回目は成功" % (c, n + 1))
        expected = chars[i + 1] if i + 1 < len(chars) else None
        eq(t.current, expected, "%s のあとは %s" % (c, expected or "（完了）"))
    eq(t.finished, True, "%d 文字すべてで完了" % len(chars))
    eq(t.position(), len(chars), "進捗表示は最後の文字で止まる")

    print("       -- b まで到達できる（9 文字目）--")
    t = Training(chars)
    for c in chars[:-1]:
        for _ in range(required):
            t.attempt(c, 0.95)
    eq(t.current, "b", "8 文字終えると b が出る")
    eq(t.position(), 9, "進捗は 9 文字目")
    st = t.attempt("b", 0.95)
    eq(st["finished"], False, "1 回目ではまだ終わらない")
    st = t.attempt("b", 0.95)
    eq(st["char_completed"], True, "2 回目で b が完了")
    eq(st["finished"], True, "b の完了で全体が終わる")

    print("       -- スキップ --")
    t = Training(chars)
    t.skip_current()
    eq(t.current, chars[1], "スキップで次の文字へ")
    eq(t.finished, False, "スキップしただけでは終わらない")
    for _ in chars[1:]:
        t.skip_current()
    eq(t.finished, True, "全部スキップすると完了")
    t = Training(chars)
    t.skip_all()
    eq(t.finished, True, "一括終了で完了")
    eq(t.current, None, "完了後の現在文字は無い")

    print("       -- 誤認識・弱いスコアで書き方を覚える --")
    t = Training(chars)
    st = t.attempt("d", 0.95)          # b を書いたつもりが d（実測でよくある取り違え）
    eq(st["correct"], False, "目的の文字でなければ失敗")
    eq(st["learned"], True, "その書き方を個人テンプレートへ登録する")
    eq(t.successes, 0, "失敗では成功回数が増えない")
    t = Training(chars)
    st = t.attempt(chars[0], good - 0.01)
    eq(st["correct"], True, "正解だが")
    eq(st["learned"], True, "スコアが低いので書き方を覚える")
    t = Training(chars)
    st = t.attempt("d", 0.95, save_ok=False)
    eq(st["rejected"], True, "衝突ガードで弾かれたら rejected")
    eq(st["learned"], False, "登録はされない")

    print("       -- 見本の始点と終点が重ならない（B は左下始まりが読み取れる）--")
    src_sample = SRC["SampleStrokes"]
    check('"b" to B' in src_sample, "b 用の見本ストロークが登録されている")
    m = re.search(r"private val B: List<Pt> = listOf\((.*?)\n    \)", src_sample, re.S)
    check(m is not None, "見本 B の定義が読める")
    if m:
        pts = [(float(a), float(bb))
               for a, bb in re.findall(r"Pt\(([\d.]+)f, ([\d.]+)f\)", m.group(1))]
        import math as _m
        gap = _m.hypot(pts[-1][0] - pts[0][0], pts[-1][1] - pts[0][1])
        tpl_b = [p for s, p in TPL.blocks["letters"] if s == "b"][0]
        tpl_gap = _m.hypot(tpl_b[-1][0] - tpl_b[0][0], tpl_b[-1][1] - tpl_b[0][1])
        check(tpl_gap < 0.01, "認識テンプレートの B は始点と終点が重なる (%.3f)" % tpl_gap)
        check(gap > 0.05,
              "見本の B は始点と終点が離れていて ● と矢印が見分けられる (%.3f)" % gap)
        check(pts[0][0] < 0.4 and pts[0][1] > 0.8,
              "見本の B の書き始めが左下 (%s)" % (pts[0],))

    print("\n=== 候補確定で未確定の子音を残さない ===")
    # 候補を選んだ時点でその語は完結しているので、打ちかけの子音は捨てる。
    ime = IME()
    typed(ime, "kak")
    eq(ime.composing(), "かk", "「か」+ 未確定の k")
    ime.commit_candidate("蚊", "か")
    eq(ime.out, "蚊", "確定した語だけが入る")
    eq(ime.composing(), "", "k が残らない")
    eq(ime.romaji, "", "未確定バッファも空")

    print("       -- 2 子音クラスタ（ky）もまとめて捨てる --")
    ime = IME()
    typed(ime, "kaky")
    eq(ime.composing(), "かky", "「か」+ 未確定の ky")
    ime.commit_candidate("蚊", "か")
    eq(ime.out, "蚊", "確定した語だけ")
    eq(ime.composing(), "", "ky ごと捨てられる")

    print("       -- 「ん」（nn）は読みに含まれるので失われない --")
    ime = IME()
    typed(ime, "kann")
    eq(ime.composing(), "かん", "画面には「かん」と見えている")
    eq(ime.reading_for_conversion(), "かん", "変換読みも「かん」")
    ime.commit_candidate("缶", ime.reading_for_conversion())
    eq(ime.out, "缶", "「ん」を含んだ読みで確定できる")
    eq(ime.composing(), "", "余分な文字は残らない")

    print("       -- 確定アンドゥに捨てた子音が復活しない --")
    ime = IME()
    typed(ime, "kak")
    ime.commit_candidate("蚊", "か")
    eq(ime.last_commit, ("蚊", "か"), "記録は捨てた子音を含まない")
    ime.stroke(BACKSPACE)
    eq(ime.out, "", "確定が取り消される")
    eq(ime.composing(), "か", "戻るのは読みだけ（k は復活しない）")

    print("       -- 画面全体で検証（InputConnection モック）--")
    m = ComposingIME().type("kak")
    eq(m.ic.whole(), "かk", "合成に k が見えている")
    m.commit_candidate("蚊", "か")
    eq(m.ic.whole(), "蚊", "画面全体が確定語だけ")
    eq(m.ic.committed(), "蚊", "確定側も同じ")
    eq(m.ic.composing(), "", "合成領域は空")

    print("       -- スペース変換 -> 文節確定では合成に取り込む（既定の仕様）--")
    # 候補タップと違い、スペースは「いま打った内容を変換しろ」という指示なので、
    # 打ちかけの子音を黙って捨てない。ローマ字として読みへ畳み込む
    # （"kan" + スペース -> 「かn」という既存の仕様と同じ扱い）。
    # 確定後に**合成として残らない**ことが重要で、そこは満たしている。
    ime = IME()
    typed(ime, "kyouk")
    eq(ime.composing(), "きょうk", "「きょう」+ 未確定の k")
    ime.stroke(SPACE)
    eq(ime.romaji, "", "スペースで未確定バッファは解消される")
    eq(ime.kana, "きょうk", "打った内容は読みへ畳み込まれる")
    ime.stroke(RETURN)
    eq(ime.composing(), "", "確定後に合成は空（residue が残らない）")
    eq(ime.romaji, "", "未確定バッファも空")

    print("\n=== かなモードの全角記号は合成へ足す（確定しない）===")
    # 「ー」は読みそのもの。「、」「。」も付けたまま変換できるのが普通の日本語 IME。
    m = ComposingIME().type("ko").symbol("-").type("hi").symbol("-")
    eq(m.ic.whole(), "こーひー", "こーひー が合成として伸びる")
    eq(m.ic.committed(), "", "確定側は空（途中で確定していない）")
    eq(m.ic.composing(), "こーひー", "全部が合成領域に入っている")

    m = ComposingIME().type("kyouha").symbol(",")
    eq(m.ic.whole(), "きょうは、", "きょうは + 、 で合成が伸びる")
    eq(m.ic.committed(), "", "確定側は空")

    m = ComposingIME().type("nihongo").symbol(".")
    eq(m.ic.whole(), "にほんご。", "。 も合成へ足される")
    eq(m.ic.committed(), "", "確定側は空")

    print("\n=== かなモードの ？ ！ は全角になり合成へ足される ===")
    eq(KANA_SYMBOL_MAP.get("?"), "？", "? -> ？ のマッピングがある")
    eq(KANA_SYMBOL_MAP.get("!"), "！", "! -> ！ のマッピングがある")
    for c in "？！":
        check(c in KANA_COMPOSING_SYMBOLS, "%s が合成へ足す対象に入っている" % c)

    m = ComposingIME().type("kyouha").symbol("?")
    eq(m.ic.whole(), "きょうは？", "きょうは + ？ で合成が伸びる")
    eq(m.ic.committed(), "", "確定側は空（途中で確定していない）")
    eq(m.ic.composing(), "きょうは？", "全部が合成領域に入っている")

    m = ComposingIME().type("sugoi").symbol("!")
    eq(m.ic.whole(), "すごい！", "すごい + ！ で合成が伸びる")
    eq(m.ic.committed(), "", "確定側は空")

    print("       -- ？ を含んだまま変換できる --")
    m = ComposingIME().type("kyouha").symbol("?").convert(["今日は？", "京は？"])
    eq(m.ic.whole(), "今日は？", "？ 込みで変換できる")
    eq(m.ic.committed(), "", "変換中はまだ確定していない")
    m.confirm()
    eq(m.ic.whole(), "今日は？", "確定して ？ 込みで入る")
    eq(m.ic.committed(), "今日は？", "確定側へ移った")

    print("       -- ？ のあとのバックスペースは ？ だけ消える --")
    m = ComposingIME().type("kyouha").symbol("?")
    m.backspace()
    eq(m.ic.whole(), "きょうは", "？ だけ消えて合成が残る")
    eq(m.ic.committed(), "", "確定側は空のまま")

    print("       -- カタカナモードでも全角 --")
    ime = IME(mode="KATAKANA")
    typed(ime, "sugoi")
    ime.stroke(TAP); ime.stroke("!")
    eq(ime.composing(), "スゴイ！", "カタカナモードでも ！ が合成へ足される")
    eq(ime.out, "", "確定しない")

    print("       -- abc モード・Extended では半角のまま --")
    ime = IME(mode="LATIN")
    typed(ime, "ok")
    ime.stroke(TAP); ime.stroke("?")
    eq(ime.out, "ok?", "abc モードでは半角 ? のまま確定")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(EXT); ime.stroke("¿")
    eq(ime.composing(), "", "Extended 経由は合成を確定する")
    check(ime.out.startswith("きょう"), "Extended の記号は半角のまま挿入される")

    print("       -- 自動英字化中は半角のまま確定 --")
    ime = IME()
    typed(ime, "str")
    eq(ime.auto_latin, True, "自動英字化中")
    ime.stroke(TAP); ime.stroke("?")
    check(ime.out.startswith("str"), "綴りが確定される")
    check("？" not in ime.out, "全角にはならない")

    print("\n=== 記号を含んだまま変換できる ===")
    m = ComposingIME().type("kyouha").symbol(",").convert(["今日は、", "京は、"])
    eq(m.ic.whole(), "今日は、", "記号込みで変換できる")
    eq(m.ic.committed(), "", "変換中はまだ確定していない")
    m.confirm()
    eq(m.ic.whole(), "今日は、", "確定して記号込みで入る")
    eq(m.ic.committed(), "今日は、", "確定側へ移った")

    print("\n=== 記号のあとのバックスペースは記号だけ消える ===")
    m = ComposingIME().type("kyouha").symbol(",")
    eq(m.ic.whole(), "きょうは、", "記号つきの合成")
    m.backspace()
    eq(m.ic.whole(), "きょうは", "記号だけ消えて合成が残る")
    eq(m.ic.committed(), "", "確定側は空のまま")
    m.backspace()
    eq(m.ic.whole(), "きょう", "続けて 1 文字ずつ消える")

    print("\n=== 未確定の子音が残っていても記号を足せる ===")
    ime = IME()
    typed(ime, "koohik")          # 末尾に未確定の "k"
    eq(ime.composing(), "こおひk", "未確定の k が残っている")
    ime.stroke(TAP); ime.stroke("-")   # Punctuation -> 「ー」
    eq(ime.composing(), "こおひkー", "k はかなへ落としてから記号を足す")
    eq(ime.out, "", "確定はしない")

    print("\n=== カタカナモードの「ー」===")
    ime = IME(mode="KATAKANA")
    typed(ime, "ko")
    ime.stroke(TAP); ime.stroke("-")
    typed(ime, "hi")
    ime.stroke(TAP); ime.stroke("-")
    eq(ime.composing(), "コーヒー", "カタカナモードでも合成が伸びる")
    eq(ime.out, "", "確定しない")

    print("\n=== 半角記号・abc モードは従来どおり確定 ===")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(TAP); ime.stroke("$")   # 「$」は全角化しないので確定
    eq(ime.out, "きょう$", "半角記号は合成を確定して挿入する")
    eq(ime.composing(), "", "合成は空になる")

    ime = IME(mode="LATIN")
    typed(ime, "abc")
    ime.stroke(TAP); ime.stroke(",")
    eq(ime.out, "abc,", "abc モードでは半角のまま確定")

    print("\n=== Extended 経由の記号は全角化しない（従来どおり）===")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(EXT); ime.stroke("-")   # Extended の「−」
    eq(ime.out.startswith("きょう"), True, "合成は確定されて挿入される")
    eq(ime.composing(), "", "合成は空")

    print("\n=== 自動英字化中は記号で確定（ASCII 扱いのまま）===")
    ime = IME()
    typed(ime, "str")
    eq(ime.auto_latin, True, "自動英字化中")
    ime.stroke(TAP); ime.stroke("-")
    eq(ime.out.startswith("str"), True, "綴りが確定される")

    print("\n=== 記号を含む読みは履歴に積まない（「ー」は積む）===")
    check(all(c in "ぁぃぅぇぉかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねの"
              "はばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんー　"
              or c in "ゔゕゖ" for c in "こーひー"),
          "「こーひー」は履歴に積める読み（ー は許可されている）")
    check("、" not in "ぁ-ゖー　", "「、」は履歴の読みとして許可されていない（ゴミが溜まらない）")

    print("\n=== Kotlin 側の配線 ===")
    src = SRC["UniStrokeIME"]
    check("isKanaComposingSymbol" in src, "全角記号の判定がある")
    check("appendSymbolToComposing" in src, "合成へ足す経路がある")
    check("KANA_COMPOSING_SYMBOLS" in src, "対象記号の一覧がある")
    for c in "ー、。":
        check(c in KANA_COMPOSING_SYMBOLS, "%s が対象に入っている" % c)
    check("inputMode != InputMode.LATIN && !autoLatin" in src,
          "自動英字化中はかなモード扱いにしない（記号も半角のまま）")
    check("if (kanaMode && isKanaComposingSymbol(text)) {" in src,
          "かなモードのときだけ全角記号を合成へ足す")

    print("\n=== InputConnection モック: 画面全体の文字列を検証 ===")
    # 「バックスペースのたびに 1 文字ずつ増える」不具合の回帰。
    # composing の中身だけでなく、確定済みテキストも含めた**全体**を見る。

    print("       -- 1. 合成中のバックスペース --")
    m = ComposingIME().type("syakenn")
    eq(m.ic.whole(), "しゃけん", "入力後の画面全体")
    eq(m.ic.committed(), "", "確定側には何も無い")
    for want in ("しゃけn", "しゃけ", "しゃ", "し", ""):
        m.backspace()
        eq(m.ic.whole(), want, "BS -> 画面全体が %r" % want)
        eq(m.ic.committed(), "", "BS -> 確定側は空のまま（漏れない）")

    print("       -- 2. 変換候補選択中のバックスペース --")
    m = ComposingIME().type("syakenn").convert(["車検", "社家権", "しゃけん"])
    eq(m.ic.whole(), "車検", "変換モードの画面全体")
    eq(m.ic.committed(), "", "変換中は何も確定されていない")
    m.cycle()
    eq(m.ic.whole(), "社家権", "候補送りでも置き換わるだけ")
    eq(m.ic.committed(), "", "候補送りで確定側へ漏れない")
    m.backspace()
    eq(m.ic.whole(), "しゃけん", "BS で変換前のひらがなに戻る")
    eq(m.ic.committed(), "", "戻すときに確定側へ漏れない")

    print("       -- 3. 変換キャンセル後に続けてバックスペース --")
    for want in ("しゃけ", "しゃ", "し", ""):
        m.backspace()
        eq(m.ic.whole(), want, "続く BS -> %r" % want)
        eq(m.ic.committed(), "", "続く BS でも確定側は空")

    print("       -- 4. 確定直後のバックスペース（確定アンドゥ）--")
    m = ComposingIME().type("syakenn").convert(["車検"]).confirm()
    eq(m.ic.whole(), "車検", "確定後の画面全体")
    eq(m.ic.committed(), "車検", "確定側に入っている")
    eq(m.ic.composing(), "", "合成は空")
    m.backspace()
    eq(m.ic.whole(), "しゃけん", "アンドゥで読みに戻る")
    eq(m.ic.committed(), "", "確定した「車検」は消えている（残らない）")
    m.backspace()
    eq(m.ic.whole(), "しゃけ", "続く BS は 1 文字ずつ")
    eq(m.ic.committed(), "", "確定側は空のまま")

    print("       -- 5. 確定 -> 再入力 -> 確定を繰り返しても増えない --")
    m = ComposingIME()
    for i in range(3):
        m.type("kyou").confirm()
    eq(m.ic.whole(), "きょう" * 3, "3 回確定してちょうど 3 語")
    eq(m.ic.composing(), "", "合成は残らない")

    print("       -- 6. 合成中に確定してもう一度合成 --")
    m = ComposingIME().type("kyou").confirm().type("tenki")
    eq(m.ic.committed(), "きょう", "確定側は 1 語だけ")
    eq(m.ic.composing(), "てんき", "合成側は新しい語だけ")
    eq(m.ic.whole(), "きょうてんき", "画面全体が重複していない")

    print("       -- 7. 変換 -> キャンセル -> 再変換 --")
    m = ComposingIME().type("kyou").convert(["今日", "京"])
    m.backspace()
    m.convert(["今日", "京"])
    eq(m.ic.whole(), "今日", "再変換しても画面は 1 語ぶん")
    eq(m.ic.committed(), "", "確定側へ漏れていない")
    m.confirm()
    eq(m.ic.whole(), "今日", "確定して 1 語ぶん")

    print("       -- 8. 確定のあとに記号をもう 1 文字コミット -> バックスペース --")
    # 【再発防止】flushComposing() が lastCommit=(かな, かな) を記録したあと、
    # 呼び出し側がさらに 1 文字コミットすると記録と実体が 1 文字ずれる。
    # 昔はそのまま deleteSurroundingText(len(surface)) していたので、
    # 「余分な 1 文字 + surface の後ろ側」が消えて surface の先頭が複製され、
    # **1 回につき 1 文字ずつ増えて**いった。
    m = ComposingIME().type("konnnichiha")
    eq(m.ic.whole(), "こんにちは", "合成中")
    # 全角記号（、。ー）はかなモードでは合成へ足されるので、
    # 「確定してからもう 1 文字コミットする」経路には半角記号を使う。
    m.emit_symbol("$")
    eq(m.ic.whole(), "こんにちは$", "記号まで含めて確定")
    eq(m.ic.committed(), "こんにちは$", "すべて確定側")
    m.backspace()
    eq(m.ic.whole(), "こんにちは", "BS は余分な記号 1 文字だけを消す")
    eq(m.ic.committed(), "こんにちは", "先頭が複製されていない")
    eq(m.ic.composing(), "", "読みが挿し直されていない")

    print("       -- 9. 同じ事故を 3 回繰り返しても 1 文字も増えない --")
    m = ComposingIME()
    for i in range(3):
        m.type("konnnichiha").emit_symbol("$").backspace()
        eq(m.ic.whole(), "こんにちは" * (i + 1), "%d 周目の画面全体" % (i + 1))
        eq(m.ic.committed(), "こんにちは" * (i + 1), "%d 周目の確定済み" % (i + 1))

    print("       -- 10. 変換確定（surface != reading）のあとに記号 --")
    m = ComposingIME().type("kyou").convert(["今日"]).confirm()
    eq(m.ic.whole(), "今日", "変換確定")
    m.emit_symbol("$")
    eq(m.ic.whole(), "今日$", "記号まで確定")
    m.backspace()
    eq(m.ic.whole(), "今日", "BS は記号だけを消す（「今きょう」にならない）")
    eq(m.ic.committed(), "今日", "確定側も同じ")
    eq(m.ic.composing(), "", "読みが挿し直されていない")

    print("       -- 11. スペース確定・数字確定・TAB 確定のあとの BS --")
    for label, op, tail in (
        ("スペース", lambda x: x.space(), " "),
        ("数字", lambda x: x.digit("5"), "5"),
        ("TAB", lambda x: x.tab(), "\t"),
    ):
        m = ComposingIME().type("konnnichiha")
        op(m)
        eq(m.ic.whole(), "こんにちは" + tail, "%s まで確定" % label)
        m.backspace()
        eq(m.ic.whole(), "こんにちは", "%s のあとの BS は 1 文字だけ消す" % label)
        eq(m.ic.committed(), "こんにちは", "%s のあとの確定済み" % label)

    print("       -- 12. 純粋な確定直後の BS は今までどおりアンドゥ --")
    m = ComposingIME().type("kyou").confirm()
    eq(m.ic.whole(), "きょう", "かなを確定")
    m.backspace()
    eq(m.ic.whole(), "きょう", "画面の文字数は変わらない")
    eq(m.ic.committed(), "", "確定は取り消されている")
    eq(m.ic.composing(), "きょう", "合成として戻っている")

    print("\n=== 認識棄却（UNRECOGNIZED）を挟んだシーケンス ===")
    print("       -- 棄却は合成にも確定済みにも触らない --")
    m = ComposingIME().type("kyou")
    before_whole, before_comp = m.ic.whole(), m.ic.composing()
    m.unrecognized()
    eq(m.ic.whole(), before_whole, "棄却で画面は変わらない")
    eq(m.ic.composing(), before_comp, "棄却で合成も変わらない")
    eq(m.ic.committed(), "", "棄却で確定側へ漏れない")

    print("       -- 棄却が挟まると確定アンドゥは無効（普通の BS になる）--")
    m = ComposingIME().type("kyou").confirm()
    m.unrecognized()
    m.backspace()
    eq(m.ic.whole(), "きょ", "棄却のあとは末尾 1 文字が消えるだけ")
    eq(m.ic.committed(), "きょ", "合成として復元されない")
    eq(m.ic.composing(), "", "読みが挿し直されていない")

    print("       -- 棄却 -> 記号確定 -> 棄却 -> BS を繰り返しても増えない --")
    m = ComposingIME()
    for i in range(3):
        m.unrecognized()
        m.type("kyou")
        m.unrecognized()
        m.emit_symbol("$")           # 半角記号（確定してからもう 1 文字コミット）
        m.unrecognized()
        m.backspace()
        eq(m.ic.whole(), "きょう" * (i + 1), "%d 周目（棄却まみれ）" % (i + 1))
        eq(m.ic.committed(), "きょう" * (i + 1), "%d 周目の確定済み" % (i + 1))

    print("       -- 棄却を任意の位置に差し込んでも画面が壊れない（総当たり）--")
    ops = [
        ("type", lambda x: x.type("ka")),
        ("emit", lambda x: x.emit_symbol(",")),
        ("space", lambda x: x.space()),
        ("bs", lambda x: x.backspace()),
        ("confirm", lambda x: x.confirm()),
    ]
    bad = []
    for i in range(len(ops)):
        for j in range(len(ops)):
            plain = ComposingIME()
            fuzz = ComposingIME()
            for k, (_, op) in enumerate((ops[i], ops[j])):
                op(plain)
                fuzz.unrecognized()
                op(fuzz)
                fuzz.unrecognized()
            # 棄却は状態を変えないので、確定アンドゥが無効になるぶんを除けば
            # 画面は同じか短くなるだけ。少なくとも「増える」ことはあってはいけない。
            if len(fuzz.ic.whole()) > len(plain.ic.whole()):
                bad.append("%s+%s: %r > %r" % (ops[i][0], ops[j][0],
                                               fuzz.ic.whole(), plain.ic.whole()))
    check(not bad, "棄却を挟んでも画面の文字数が増えない%s"
          % ("" if not bad else " -> " + "; ".join(bad[:4])))

    print("\n=== 学習リセット相当の状態変化を挟んだシーケンス ===")
    # 学習データのリセットは PersonalTemplateStore と認識器だけの話で、
    # 合成領域・確定アンドゥの状態には一切触れてはいけない。
    # 実機ではリセット後に「棄却が増える + バックスペースへの誤認が増える」ので、
    # その並びで文字が増えないことをここで固定する。
    kt = SRC["UniStrokeIME"]
    check("personalStore.reloadIfChanged()" in kt,
          "リセットは入力欄を開くたびに読み直される")
    check("refreshLearnedTemplates()" in kt, "認識器のテンプレートを作り直している")
    reset_scope = kt[kt.index("private fun refreshLearnedTemplates"):]
    reset_scope = reset_scope[:reset_scope.index("\n    }")]
    check("setComposingText" not in reset_scope and "commitText" not in reset_scope
          and "clearComposing" not in reset_scope,
          "テンプレート再読み込みは合成領域に触らない")

    print("       -- リセット後（棄却増 + BACKSPACE 誤認）でも増えない --")
    m = ComposingIME()
    for i in range(3):
        m.type("konnnichiha")
        m.unrecognized()             # 認識悪化で棄却
        m.emit_symbol("$")           # 半角記号（確定してからもう 1 文字コミット）
        m.backspace()                # SPACE のつもりが BACKSPACE に誤認
        eq(m.ic.whole(), "こんにちは" * (i + 1), "リセット後 %d 周目" % (i + 1))
    eq(m.ic.committed(), "こんにちは" * 3, "確定済みも 3 語ちょうど")

    print("\n=== 入力欄の切替 / ic == null を挟んだシーケンス ===")
    print("       -- 合成中に入力欄が変わる --")
    first = InputConnectionMock(name="first")
    m = ComposingIME(first).type("kyou")
    eq(first.whole(), "きょう", "1 つ目の欄には合成が出ている")
    second = InputConnectionMock(name="second")
    m.switch_field(second)
    eq(first.whole(), "きょう", "1 つ目の欄の内容は確定されて残る")
    eq(first.composing(), "", "1 つ目の欄に合成が残っていない")
    eq(second.whole(), "", "2 つ目の欄は空のまま")
    m.type("tenki")
    eq(second.whole(), "てんき", "2 つ目の欄に新しい合成")
    eq(first.whole(), "きょう", "1 つ目の欄は書き換わらない")

    print("       -- 確定直後に入力欄が変わったら、その BS でアンドゥしない --")
    first = InputConnectionMock(name="first")
    m = ComposingIME(first).type("kyou").confirm()
    second = InputConnectionMock("既存の文章", name="second")
    m.switch_field(second)
    m.backspace()
    eq(second.whole(), "既存の文", "新しい欄では普通のバックスペース")
    eq(first.whole(), "きょう", "前の欄の確定は取り消されない")

    print("       -- 記録がずれたまま新しい欄を触っても選択を壊さない --")
    # 【防御】composingShown が実体とずれていても、新しい欄へ
    # setComposingText("") を送ってはいけない（選択中のテキストが消える）。
    # 所有者チェックがないと、ここで「選択されている文章」が丸ごと消えていた。
    second = InputConnectionMock("選択されている文章", name="second")
    second.set_selection(0, len(second.text))
    m = ComposingIME(first).type("kyou")
    m.ic = second                      # 記録は first のまま = ずれている状態
    m.kana = ""
    m.romaji = ""                      # 合成は空 -> flush は clearComposing だけ
    m._flush()
    eq(second.whole(), "選択されている文章", "選択中のテキストが消えない")
    eq(m.composing_shown, False, "ずれた記録は落ちる")

    print("       -- 記録だけずらしても選択を巻き込まない --")
    third = InputConnectionMock("消えてはいけない", name="third")
    third.set_selection(0, len(third.text))
    m = ComposingIME(third)
    m.composing_shown = True           # 実体は無いのに出ていることになっている
    m.composing_owner = third
    m._clear_composing(third)
    eq(third.whole(), "消えてはいけない", "選択中のテキストが消えない")
    eq(m.composing_shown, False, "記録は落ちる")

    print("       -- ic == null を挟む --")
    m = ComposingIME().type("kyou")
    m.unbind()
    m.type("tenki")                    # 接続が無い間の入力は落ちるだけ
    third = InputConnectionMock("既存", name="third")
    m.rebind(third)
    eq(third.whole(), "既存", "繋ぎ直した欄は書き換わらない")
    m.backspace()
    eq(third.whole(), "既", "確定アンドゥではなく普通の BS になる")
    m.type("ka")
    eq(third.whole(), "既か", "その後は普通に入力できる")

    print("       -- 変換中に接続が切れても、確定を記録しない --")
    m = ComposingIME().type("kyou").convert(["今日"])
    m.ic = None
    m._finish_conversion()
    eq(m.last_commit, None, "接続が無い確定は undo に記録しない")
    fourth = InputConnectionMock("別の欄", name="fourth")
    m.rebind(fourth)
    m.backspace()
    eq(fourth.whole(), "別の", "存在しない確定を取り消しに行かない")

    print("       -- 合成領域を触る経路が 1 本にまとまっているか --")
    kt = SRC["UniStrokeIME"]
    check(kt.count("ic.setComposingText(") == 2,
          "setComposingText の呼び出しは clearComposing / showComposing の 2 箇所だけ "
          "(%d 箇所)" % kt.count("ic.setComposingText("))
    check(kt.count("ic.finishComposingText()") == 1,
          "finishComposingText の呼び出しは clearComposing の 1 箇所だけ "
          "(%d 箇所)" % kt.count("ic.finishComposingText()"))
    check("private fun showComposing(" in kt, "合成表示の入口が 1 本化されている")
    check("if (!composingShownOn(ic))" in kt,
          "合成領域を出していないときは InputConnection に触らない")
    check("private var composingOwner: InputConnection? = null" in kt,
          "合成領域の記録が「どの InputConnection に対してか」まで持っている")
    check("composingShown && composingOwner === ic" in kt,
          "入力欄が変わったら『出していない』と見なす（新しい欄を壊さない）")
    check("private fun noteComposingGone()" in kt,
          "記録だけ落とす（IC に触らない）経路がある")
    check("showComposing(ic, currentConversionText())" in kt,
          "変換中の合成表示も同じ経路を通る（フラグを取りこぼさない）")

    print("       -- アプリへの確定が 1 本にまとまっているか --")
    check(kt.count("ic.commitText(") == 1,
          "commitText の呼び出しは commitFinal の 1 箇所だけ "
          "(%d 箇所)" % kt.count("ic.commitText("))
    check("private fun commitFinal(" in kt, "確定の入口が 1 本化されている")
    check("noteCommitted" not in kt,
          "『合成だけ閉じて undo は触らない』という中途半端な記録が残っていない")
    body = kt[kt.index("private fun commitFinal("):]
    body = body[:body.index("\n    }")]
    check("clearUndo()" in body and "noteCommit(" in body,
          "確定のたびに undo 記録を書き直すか捨てるかしている")
    check("override fun onStartInput(" in kt,
          "入力欄が変わったら合成と確定の記録を落としている")
    check("override fun onUnbindInput()" in kt,
          "接続が外れたときも記録を落としている")

    print("\n=== リターン: 合成中は確定のみ、空なら改行 ===")
    ime = IME()
    typed(ime, "kyou")
    eq(ime.composing(), "きょう", "合成中")
    ime.stroke(RETURN)
    eq(ime.out, "きょう", "1 回目のリターンは確定のみ（改行しない）")
    eq(ime.composing(), "", "合成は空になる")
    ime.stroke(RETURN)
    eq(ime.out, "きょう\n", "2 回目のリターンで改行が入る")

    print("\n=== 未確定ローマ字だけでもリターンは確定のみ ===")
    ime = IME()
    typed(ime, "k")
    eq(ime.composing(), "k", "未確定の子音だけ")
    ime.stroke(RETURN)
    eq(ime.out, "k", "確定のみ")
    eq("\n" in ime.out, False, "改行は入らない")

    print("\n=== 「ん」で終わる合成でもリターンは確定のみ ===")
    ime = IME()
    typed(ime, "honn")
    ime.stroke(RETURN)
    eq(ime.out, "ほん", "「ほん」が確定される")
    eq("\n" in ime.out, False, "改行は入らない")

    print("\n=== 自動英字化中のリターンも確定のみ ===")
    ime = IME()
    typed(ime, "str")
    eq(ime.auto_latin, True, "自動英字化中")
    ime.stroke(RETURN)
    eq(ime.out, "str", "綴りが確定される")
    eq("\n" in ime.out, False, "改行は入らない")
    ime.stroke(RETURN)
    eq(ime.out, "str\n", "空になってからのリターンで改行")

    print("\n=== 何も入力していないときのリターンは改行 ===")
    ime = IME()
    ime.stroke(RETURN)
    eq(ime.out, "\n", "そのまま改行")
    ime.stroke(RETURN)
    eq(ime.out, "\n\n", "続けて改行できる")

    print("\n=== 変換中のリターンは全文節確定のみ ===")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(SPACE)          # 変換要求（合成のまま）
    eq(ime.kana, "きょう", "変換対象が確定かなに入る")
    ime.stroke(RETURN)
    eq(ime.out, "きょう", "確定のみ")
    eq("\n" in ime.out, False, "改行は入らない")

    print("\n=== リターン確定でも確定アンドゥは効く ===")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(RETURN)
    eq(ime.last_commit, ("きょう", "きょう"), "リターンによる確定も記録される")
    ime.stroke(BACKSPACE)
    eq(ime.out, "", "バックスペースで確定前に戻せる")
    eq(ime.composing(), "きょう", "合成状態が復元される")

    print("\n=== リターンの Kotlin 側配線 ===")
    src = SRC["UniStrokeIME"]
    check("if (composingLength() > 0) {" in src, "合成中かどうかで分岐している")
    check("sendEnter(ic)" in src, "改行の送出が分離されている")
    check(src.count("KeyEvent.KEYCODE_ENTER") == 2,
          "Enter の送出は 1 箇所（合成が空のときだけ）")
    check("performEditorAction(action)" in src,
          "検索・送信などのアクションを要求する欄ではそちらを実行する")
    check("IME_FLAG_NO_ENTER_ACTION" in src,
          "改行が正解の欄（NO_ENTER_ACTION）は従来どおり改行する")

    print("\n=== 「ん」で終わる語を確定しても「ん」が取り残されない ===")
    # 実機バグ: syakenn -> 変換 -> 確定 のあとに余分な「ん」が出た。
    # 原因は "nn" が未確定のまま残り、表示（しゃけん）と変換対象（しゃけ）がずれていたこと。
    ime = IME()
    typed(ime, "syakenn")
    eq(ime.composing(), "しゃけん", "画面には「しゃけん」と見えている")
    eq(ime.reading_for_conversion(), "しゃけん",
       "変換に使う読みも「しゃけん」（表示とずれない）")
    ime.commit_candidate("車検", ime.reading_for_conversion())
    eq(ime.out, "車検", "候補を確定すると「車検」だけが入る")
    eq(ime.composing(), "", "余分な「ん」が残らない")
    eq(ime.romaji, "", "未確定バッファも空になる")

    print("\n=== 末尾が「ん」の語（候補確定）===")
    for spell, kana, surface in [
        ("honn", "ほん", "本"),
        ("nihonn", "にほん", "日本"),
        ("kantann", "かんたん", "簡単"),
        ("shinbunn", "しんぶん", "新聞"),
        ("mikann", "みかん", "蜜柑"),
    ]:
        ime = IME()
        typed(ime, spell)
        eq(ime.composing(), kana, "%s -> %s と見える" % (spell, kana))
        eq(ime.reading_for_conversion(), kana, "%s の変換読みも %s" % (spell, kana))
        ime.commit_candidate(surface, kana)
        eq(ime.out, surface, "%s を確定して %s だけが入る" % (kana, surface))
        eq(ime.composing(), "", "%s の確定後に何も残らない" % kana)

    print("\n=== 途中に「ん」がある語 ===")
    for spell, kana in [
        ("konnichiha", "こんにちは"),
        ("kinnyoubi", "きんようび"),
        ("zennbu", "ぜんぶ"),
        ("annnai", "あんない"),
        ("shinnkansenn", "しんかんせん"),
    ]:
        ime = IME()
        typed(ime, spell)
        eq(ime.composing(), kana, "%s -> %s" % (spell, kana))
        eq(ime.reading_for_conversion(), kana, "%s の変換読み" % spell)

    print("\n=== 「ん」で終わる語をスペースで変換する場合 ===")
    ime = IME()
    typed(ime, "syakenn")
    ime.stroke(SPACE)
    eq(ime.kana, "しゃけん", "スペースで「しゃけん」が確定かなに入る")
    eq(ime.romaji, "", "未確定は空になる")
    eq(ime.out, "", "スペースだけでは何も確定しない（変換要求）")

    print("\n=== 「ん」だけを入力してもスペースで変換要求になる ===")
    ime = IME()
    typed(ime, "nn")
    eq(ime.composing(), "ん", "nn -> ん と見える")
    eq(ime.reading_for_conversion(), "ん", "読みも「ん」")
    ime.stroke(SPACE)
    eq(ime.out, "", "スペースで確定せず変換要求になる")
    eq(ime.kana, "ん", "「ん」が変換対象として残る")

    print("\n=== 単独の n は従来どおり（「ん」にしない）===")
    ime = IME()
    typed(ime, "kan")
    eq(ime.composing(), "かn", "kan は「かn」のまま")
    eq(ime.reading_for_conversion(), "か",
       "未確定の n はかなになっていないので読みに含めない")
    ime2 = IME()
    typed(ime2, "kan")
    ime2.commit_candidate("蚊", "か")
    eq(ime2.composing(), "", "候補を選んだら未確定の n は捨てる（残さない）")
    eq(ime2.out, "蚊", "確定した語だけが入る")

    print("\n=== nnn の連続 ===")
    ime = IME()
    typed(ime, "nnn")
    eq(ime.composing(), "んn", "nnn -> 「ん」+ 未確定の n")
    eq(ime.reading_for_conversion(), "ん", "読みは確定ぶんの「ん」だけ")
    typed(ime, "a")
    eq(ime.composing(), "んな", "nnna -> んな")

    print("\n=== Kotlin 側の配線 ===")
    src = SRC["UniStrokeIME"]
    check("readingForConversion()" in src, "変換読みの組み立てが 1 箇所に集約されている")
    check("settledPending" in SRC["RomajiConverter"],
          "RomajiConverter に「表示上かなになっている未確定分」がある")
    check("romaji.setLength(0)" in src,
          "候補確定時に未確定ローマ字を捨てている")
    check("kana.toString() != reading" not in src,
          "変換の鮮度判定も同じ読みで行っている（kana 直参照が残っていない）")

    print("\n=== 全選択ボタン: 1 回目で全選択 / 2 回目で全削除 ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "tenki")
    ime._flush()
    eq(ime.out, "きょうてんき", "2 語を確定した")
    eq(ime.select_all(), "selected", "1 回目のタップは全選択")
    eq(ime.selection, (0, 6), "テキスト全体が選択される")
    eq(ime.select_all(), "deleted", "2 回目のタップで削除")
    eq(ime.out, "", "全部消える")
    eq(ime.selection, None, "選択も解除される")
    eq(ime.select_all(), "selected", "空の状態でもう一度押しても壊れない")

    print("\n=== 長押しで文節選択 -> タップで文節削除 ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "tenki")
    ime._flush()
    eq(ime.select_last_commit(), True, "長押しで直前確定を選択")
    eq(ime.selection, (3, 6), "「てんき」だけが選択される")
    eq(ime.select_all(), "deleted", "続けてタップすると、その範囲だけ削除")
    eq(ime.out, "きょう", "直前確定だけが消え、前の語は残る")

    print("\n=== 長押しを 2 回でも削除になる ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    eq(ime.select_last_commit(), True, "1 回目の長押しで選択")
    eq(ime.select_last_commit(), "deleted", "2 回目の長押しでも削除")
    eq(ime.out, "", "選択範囲が消える")

    print("\n=== ユーザーが自分で選んだ範囲は消さない ===")
    ime = IME()
    typed(ime, "kyou")
    ime._flush()
    typed(ime, "tenki")
    ime._flush()
    ime.selection = (1, 3)     # アプリ側で部分選択された想定
    ime.own_selection = None
    eq(ime.select_all(), "selected", "こちらが選んだ範囲でなければ削除しない")
    eq(ime.out, "きょうてんき", "テキストは消えない")
    eq(ime.selection, (0, 6), "全選択に切り替わる")

    print("\n=== 全選択ボタンの Kotlin 側配線 ===")
    src = SRC["UniStrokeIME"]
    check("deleteOwnSelection" in src, "選択削除の実装がある")
    check("ownSelection" in src, "こちらが選んだ範囲を覚えている")
    check("val whole = start == 0 && end >= length" in src,
          "テキスト全体が選択されているかを見ている")
    check("if (!whole && !mine) return false" in src,
          "こちらが選んだ範囲でなければ削除しない")
    check("composingShown" in src,
          "setComposingText(\"\") が選択範囲を巻き込まないようにしている")

    print("\n=== 長押しの Kotlin 側配線 ===")
    src = SRC["UniStrokeIME"]
    view = SRC["UniStrokeView"]
    check("fun onSelectLastCommit()" in view, "Listener に長押しのコールバックがある")
    check("selectLongPressRunnable" in view, "全選択ボタンの長押し検出がある")
    check("selectLongPressFired" in view, "長押し発火時に通常タップを抑制している")
    check("postDelayed(selectLongPressRunnable, LONG_PRESS_MS)" in view,
          "[?] と同じ長押し時間を使っている")
    check("override fun onSelectLastCommit()" in src, "IME 側に実装がある")
    check("ic.setSelection(start, cursor)" in src, "直前確定の範囲を選択している")
    check("performContextMenuAction(android.R.id.selectAll)" in src,
          "無効なときは全選択へ落としている")

    print("\n=== 確定アンドゥの Kotlin 側配線 ===")
    src = SRC["UniStrokeIME"]
    check("undoLastCommit" in src, "確定アンドゥの実装がある")
    check("noteCommit" in src, "確定内容を記録している")
    undo_body = src[src.index("private fun undoLastCommit("):]
    undo_body = undo_body[:undo_body.index("\n    }")]
    # 【再発防止】無検証の deleteSurroundingText は「消しすぎ + 読みの挿し直し」で
    # 文字を増やす。消す前に必ずカーソル直前を照合すること
    # （selectLastCommit:getTextBeforeCursor と同じ強度にそろえる）。
    check("getTextBeforeCursor(undo.surface.length, 0)" in undo_body,
          "削除の前にカーソル直前が本当にその文字列か確かめている")
    check(undo_body.index("val before = ic.getTextBeforeCursor")
          < undo_body.index("if (!ic.deleteSurroundingText"),
          "照合してから削除している（順序）")
    check("if (before?.toString() != undo.surface) return false" in undo_body,
          "ずれていたら何もせず false（通常のバックスペースへ落ちる）")
    check("deleteSurroundingText(undo.surface.length, 0)" in undo_body,
          "確定した文字列ぶんだけ削除している")
    check("if (symbol != StrokeTemplates.BACKSPACE) clearUndo()" in src,
          "バックスペース以外のストロークでアンドゥを無効化している")
    check(src.count("clearUndo()") >= 6,
          "カーソル移動・全選択・モード切替・入力欄の変更などでも無効化している")

    print("\n=== 確定アンドゥ: 実ストローク経路（_flush 直呼びではない）===")
    print("       -- かな確定 -> 記号 -> BACKSPACE --")
    ime = IME()
    typed(ime, "konnnichiha")
    ime.stroke(TAP)          # Punctuation shift
    # 全角記号（、。ー）はかなモードでは合成へ足されるので、
    # 「確定してからもう 1 文字コミットする」経路の検証には半角記号を使う。
    ime.stroke("$")
    eq(ime.out, "こんにちは$", "記号まで確定")
    ime.stroke(BACKSPACE)
    eq(ime.out, "こんにちは", "BS は記号 1 文字だけ消す")
    eq(ime.composing(), "", "読みが合成として挿し直されない")

    print("       -- 同じ並びを 3 回繰り返しても増えない --")
    ime = IME()
    for i in range(3):
        typed(ime, "konnnichiha")
        ime.stroke(TAP)
        ime.stroke("$")
        ime.stroke(BACKSPACE)
        eq(ime.out + ime.composing(), "こんにちは" * (i + 1), "%d 周目" % (i + 1))

    print("       -- スペース確定・数字確定のあとの BACKSPACE --")
    ime = IME("KATAKANA")
    typed(ime, "konnnichiha")
    ime.stroke(SPACE)        # カナモードなので変換ではなく空白の確定
    eq(ime.out, "コンニチハ ", "空白まで確定")
    ime.stroke(BACKSPACE)
    eq(ime.out + ime.composing(), "コンニチハ", "空白 1 文字だけ消える")
    ime = IME()
    typed(ime, "konnnichiha")
    ime.stroke("5")
    eq(ime.out, "こんにちは5", "数字まで確定")
    ime.stroke(BACKSPACE)
    eq(ime.out + ime.composing(), "こんにちは", "数字 1 文字だけ消える")

    print("       -- 変換確定（surface != reading）のあとの記号 -> BACKSPACE --")
    ime = IME()
    typed(ime, "kyou")
    ime.commit_candidate("今日", "きょう")
    ime.stroke(TAP)
    ime.stroke("$")          # 半角記号は従来どおり確定する
    eq(ime.out, "今日$", "記号まで確定")
    ime.stroke(BACKSPACE)
    eq(ime.out + ime.composing(), "今日", "「今きょう」にならない")

    print("       -- 棄却（UNRECOGNIZED）を挟んでも増えない --")
    ime = IME()
    for i in range(3):
        ime.stroke(UNRECOGNIZED)
        typed(ime, "kyou")
        ime.stroke(UNRECOGNIZED)
        ime.stroke(TAP)
        ime.stroke(",")
        ime.stroke(UNRECOGNIZED)
        ime.stroke(BACKSPACE)
        eq(ime.out + ime.composing(), "きょう" * (i + 1), "%d 周目（棄却入り）" % (i + 1))

    print("       -- 純粋な確定直後の BACKSPACE は今までどおりアンドゥ --")
    ime = IME()
    typed(ime, "kyou")
    ime.stroke(RETURN)       # 合成中のリターンは確定のみ
    eq(ime.out, "きょう", "確定した")
    ime.stroke(BACKSPACE)
    eq(ime.out, "", "確定が取り消され")
    eq(ime.composing(), "きょう", "合成として戻る")

    print("\n=== Kotlin 側の配線 ===")
    src = SRC["UniStrokeIME"]
    check("looksNonJapanese" in src, "IME が日本語らしさ判定を呼ぶ")
    check("wordRaw" in src, "単語単位の生ローマ字を保持している")
    check("revertAutoLatin" in src, "かな解釈へ戻す経路がある")
    check("endWord()" in src, "単語区切りで状態をクリアしている")
    check("if (autoLatin) {\n            rebuildCandidates()" in src,
          "自動英字化中も候補バーを組み直す（かな解釈への逃げ道を出す）")
    check(src.count("endWord()") >= 5,
          "確定・変換確定・候補確定・リセットなど複数経路で単語を区切っている")

    print()
    if FAILURES:
        print("FAILED (%d)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        return 1
    print("test_ime_sequence: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
