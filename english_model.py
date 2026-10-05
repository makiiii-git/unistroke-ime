#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""英語版（EnglishText / EnglishDictionary / EnglishPredictor と IME の英語分岐）の Python モデル。

unistroke_model.py と同じ考え方で、定数は Kotlin のソースから読み取り、
挙動だけを Python に写す。Kotlin の定数を変えればテストは自動的に追随する。

    from english_model import Text, Dictionary, Predictor, EnglishIME
"""

from __future__ import annotations

import os
import re
import struct
from typing import Dict, List, Optional, Tuple

from unistroke_model import ROOT, TPL, read

SRC: Dict[str, str] = {
    n: read(n + ".kt")
    for n in (
        "AppLanguage",
        "EnglishText",
        "EnglishDictionary",
        "EnglishPredictor",
        "LocalizedActivity",
        "Prefs",
        "UniStrokeIME",
        "UniStrokeView",
        "VoiceCommands",
        "VoiceInput",
    )
}

DIC_PATH = os.environ.get(
    "UNISTROKE_ENGLISH_DIC",
    os.path.join(ROOT, "app", "src", "main", "assets", "english.dic"),
)

# --------------------------------------------------------------- Kotlin 字句


def _decode(literal: str) -> str:
    """Kotlin の文字列リテラルの中身（引用符なし）を実際の文字列へ。"""
    out = []
    i = 0
    while i < len(literal):
        c = literal[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        nxt = literal[i + 1]
        if nxt == "u":
            out.append(chr(int(literal[i + 2:i + 6], 16)))
            i += 6
            continue
        out.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
        i += 2
    return "".join(out)


def str_const(src: str, name: str) -> str:
    m = re.search(r'const val %s(?::\s*\w+)?\s*=\s*"((?:[^"\\]|\\.)*)"' % name, src)
    if not m:
        raise KeyError(name)
    return _decode(m.group(1))


def int_const(src: str, name: str) -> int:
    m = re.search(r"const val %s(?::\s*\w+)?\s*=\s*(0x[0-9A-Fa-f]+|\d[\d_]*)" % name, src)
    if not m:
        raise KeyError(name)
    return int(m.group(1).replace("_", ""), 0)


# ---------------------------------------------------------------- EnglishText

_T = SRC["EnglishText"]


class Text:
    """EnglishText.kt の写し。"""

    APOSTROPHE = str_const(_T, "APOSTROPHE")
    TIGHT_PUNCTUATION = str_const(_T, "TIGHT_PUNCTUATION")
    MIN_LEARN_LENGTH = int_const(_T, "MIN_LEARN_LENGTH")
    MAX_LEARN_LENGTH = int_const(_T, "MAX_LEARN_LENGTH")
    OPENERS = str_const(_T, "OPENERS")

    @staticmethod
    def is_apostrophe(ch: str) -> bool:
        return ch in ("'", "’")

    @classmethod
    def key(cls, typed: str) -> str:
        out = []
        for ch in typed:
            if "a" <= ch <= "z":
                out.append(ch)
            elif "A" <= ch <= "Z":
                out.append(ch.lower())
            elif cls.is_apostrophe(ch):
                continue
            else:
                return ""
        return "".join(out)

    @staticmethod
    def apply_case(word: str, typed: str) -> str:
        if not word:
            return word
        letters = [ch for ch in typed if ch.isalpha()]
        uppers = [ch for ch in letters if ch.isupper()]
        if len(letters) >= 2 and len(uppers) == len(letters):
            return word.upper()
        if letters and letters[0].isupper():
            return word[0].upper() + word[1:]
        return word

    @staticmethod
    def is_capitalized(word: str) -> bool:
        return (len(word) >= 2 and word[0].isupper()
                and not any(ch.isupper() for ch in word[1:]))

    @staticmethod
    def is_all_caps(word: str) -> bool:
        letters = [ch for ch in word if ch.isalpha()]
        return len(letters) >= 2 and all(ch.isupper() for ch in letters)

    @classmethod
    def is_learnable(cls, word: str) -> bool:
        if not (cls.MIN_LEARN_LENGTH <= len(word) <= cls.MAX_LEARN_LENGTH):
            return False
        letters = 0
        for ch in word:
            if ch.isalpha():
                letters += 1
            elif not cls.is_apostrophe(ch):
                return False
        return letters >= cls.MIN_LEARN_LENGTH

    @classmethod
    def is_word_char(cls, ch: str) -> bool:
        return ch.isalnum() or cls.is_apostrophe(ch)

    @staticmethod
    def _is_blank(ch: str) -> bool:
        return ch != "\n" and ch.isspace()

    @classmethod
    def word_delete_length(cls, before: str) -> int:
        i = len(before)
        if i == 0:
            return 0
        if before[i - 1] == "\n":
            return 1
        while i > 0 and cls._is_blank(before[i - 1]):
            i -= 1
        if i == 0:
            return len(before)
        word = cls.is_word_char(before[i - 1])
        while i > 0:
            ch = before[i - 1]
            if ch == "\n" or cls._is_blank(ch) or cls.is_word_char(ch) != word:
                break
            i -= 1
        return len(before) - i

    @classmethod
    def needs_space_before(cls, before: Optional[str]) -> bool:
        if before is None:
            return False
        if before.isspace():
            return False
        return before not in cls.OPENERS


# ----------------------------------------------------------- EnglishDictionary

_D = SRC["EnglishDictionary"]
HEADER_BYTES = int_const(_D, "HEADER_BYTES")
FORMAT_VERSION = int_const(_D, "FORMAT_VERSION")
MAX_KEY_LENGTH = int_const(_D, "MAX_KEY_LENGTH")
MAGIC = b"UNIENG1\x00"


class Dictionary:
    """EnglishDictionary.kt の写し（english.dic を読む）。"""

    def __init__(self, path: str):
        with open(path, "rb") as f:
            self.buf = f.read()
        b = self.buf
        if len(b) < HEADER_BYTES or b[:8] != MAGIC:
            raise ValueError("english.dic ではない")
        (self.version, self.word_count, self.offset_off, self.freq_off,
         self.blob_off, self.blob_len) = struct.unpack_from("<6I", b, 8)

    # ---- 低レベル
    def _start(self, i: int) -> int:
        return struct.unpack_from("<I", self.buf, self.offset_off + 4 * i)[0]

    def frequency(self, i: int) -> int:
        return self.buf[self.freq_off + i]

    def entry(self, i: int) -> bytes:
        return self.buf[self.blob_off + self._start(i):self.blob_off + self._start(i + 1)]

    def key(self, i: int) -> bytes:
        return self.entry(i).split(b"\t", 1)[0]

    def word(self, i: int) -> str:
        return self.entry(i).split(b"\t", 1)[-1].decode("utf-8")

    def _compare_prefix(self, i: int, q: bytes) -> int:
        k = self.key(i)
        for d in range(len(q)):
            if d >= len(k):
                return -1
            if k[d] != q[d]:
                return -1 if k[d] < q[d] else 1
        return 0

    def prefix_range(self, q: bytes) -> Tuple[int, int]:
        lo, hi = 0, self.word_count
        while lo < hi:
            mid = (lo + hi) >> 1
            if self._compare_prefix(mid, q) < 0:
                lo = mid + 1
            else:
                hi = mid
        start = lo
        hi = self.word_count
        while lo < hi:
            mid = (lo + hi) >> 1
            if self._compare_prefix(mid, q) <= 0:
                lo = mid + 1
            else:
                hi = mid
        return start, lo

    @staticmethod
    def _encode(key: str) -> Optional[bytes]:
        if not key or len(key) > MAX_KEY_LENGTH:
            return None
        if any(not ("a" <= ch <= "z") for ch in key):
            return None
        return key.encode("ascii")

    # ---- 検索
    def complete(self, key: str, limit: int) -> List[Tuple[str, int, bool]]:
        """[(表記, 頻度, 完全一致か), ...]。並びは EnglishDictionary.complete と同じ。"""
        if limit <= 0:
            return []
        q = self._encode(key)
        if q is None:
            return []
        i, to = self.prefix_range(q)
        out: List[Tuple[str, int, bool]] = []
        while i < to and len(self.key(i)) == len(q):
            if len(out) < limit:
                out.append((self.word(i), self.frequency(i), True))
            i += 1
        k = limit - len(out)
        if k <= 0:
            return out
        top: List[Tuple[int, int]] = []  # (頻度, 番号)。頻度の降順、同点は番号の昇順
        for j in range(i, to):
            f = self.frequency(j)
            if len(top) < k or f > top[-1][0]:
                p = len(top)
                while p > 0 and top[p - 1][0] < f:
                    p -= 1
                top.insert(p, (f, j))
                del top[k:]
        out.extend((self.word(j), f, False) for f, j in top)
        return out

    def has_word(self, word: str) -> bool:
        q = self._encode(Text.key(word))
        if q is None:
            return False
        i, to = self.prefix_range(q)
        while i < to and len(self.key(i)) == len(q):
            if self.word(i) == word:
                return True
            i += 1
        return False


# ------------------------------------------------------------ EnglishPredictor

_P = SRC["EnglishPredictor"]
MAX_CANDIDATES = int_const(_P, "MAX_CANDIDATES")
MAX_FROM_HISTORY = int_const(_P, "MAX_FROM_HISTORY")
MIN_USES_UNKNOWN = int_const(_P, "MIN_USES_UNKNOWN")
EXTRA_HITS = int_const(_P, "EXTRA_HITS")
EXACT_BONUS = int_const(_P, "EXACT_BONUS")
MAX_HISTORY = int_const(_P, "MAX_HISTORY")

HOUR = 60 * 60 * 1000
DAY = 24 * HOUR
WEEK = 7 * DAY


class Predictor:
    """EnglishPredictor.kt の写し（永続化は除く）。"""

    def __init__(self, dictionary: Optional[Dictionary]):
        self.dictionary = dictionary
        self.history: Dict[str, dict] = {}

    @staticmethod
    def _score(e: dict, now: int) -> float:
        age = now - e["t"]
        recency = 3.0 if age < HOUR else 2.0 if age < DAY else 1.0 if age < WEEK else 0.0
        return e["n"] + recency

    def predict(self, typed: str, now: int, limit: int = MAX_CANDIDATES) -> List[str]:
        key = Text.key(typed)
        if not key:
            return []
        out: List[str] = []

        def add(word: str) -> None:
            if len(out) >= limit:
                return
            shown = Text.apply_case(word, typed)
            if shown == typed or shown in out:
                return
            out.append(shown)

        def contraction(hit) -> bool:
            return hit[2] and any(Text.is_apostrophe(ch) for ch in hit[0])

        hits = self.dictionary.complete(key, limit + EXTRA_HITS) if self.dictionary else []
        for hit in hits:
            if contraction(hit):
                add(hit[0])
        mine = [e for e in self.history.values()
                if e["key"].startswith(key) and (e["known"] or e["n"] >= MIN_USES_UNKNOWN)]
        mine.sort(key=lambda e: (-self._score(e, now), len(e["w"])))
        for e in mine[:MAX_FROM_HISTORY]:
            add(e["w"])
        rest = [h for h in hits if not contraction(h)]
        # sorted は安定なので、同点なら辞書が返した順（完全一致が先）のまま
        rest.sort(key=lambda h: -(h[1] + (EXACT_BONUS if h[2] else 0)))
        for hit in rest:
            add(hit[0])
        return out

    def canonical(self, word: str) -> str:
        d = self.dictionary
        if d is None:
            return word
        lower = word.lower()
        if Text.is_capitalized(word):
            return lower if d.has_word(lower) else word
        if Text.is_all_caps(word):
            return lower if (not d.has_word(word) and d.has_word(lower)) else word
        return word

    def record(self, word: str, now: int) -> None:
        if not Text.is_learnable(word):
            return
        w = self.canonical(word)
        e = self.history.pop(w, None)
        if e is None:
            known = self.dictionary.has_word(w) if self.dictionary else True
            e = {"w": w, "key": Text.key(w), "n": 0, "t": now, "known": known}
        e["n"] += 1
        e["t"] = now
        self.history[w] = e
        while len(self.history) > MAX_HISTORY:
            oldest = min(self.history.values(), key=lambda x: x["t"])
            del self.history[oldest["w"]]


# ----------------------------------------------------------------- IME の英語分岐

S = TPL.symbols
SPACE, BACKSPACE, RETURN = S["SPACE"], S["BACKSPACE"], S["RETURN"]
SHIFT, TAP = S["SHIFT"], S["TAP"]


def sentence_start(before: str) -> bool:
    """InputConnection.getCursorCapsMode（文頭を大文字にする欄）の簡略モデル。

    テキストの先頭・改行の直後・「. ! ?」に空白が続いた直後を文頭とみなす。
    """
    if not before:
        return True
    if before[-1] == "\n":
        return True
    stripped = before.rstrip(" ")
    if stripped == before:
        return False
    return not stripped or stripped[-1] in ".!?\n"


class EnglishIME:
    """UniStrokeIME の英語版の入力ディスパッチの写し。

    カーソルは常にテキストの末尾にあるものとして、確定済みの文字列を out に持つ。
    """

    def __init__(self, predictor: Optional[Predictor] = None, predict_here: bool = True,
                 caps_field: bool = True, auto_cap: bool = True, learning: bool = True,
                 text: str = ""):
        self.predictor = predictor or Predictor(None)
        self.predict_here = predict_here
        self.caps_field = caps_field     # 入力欄が「文頭を大文字に」を求めているか
        self.auto_cap = auto_cap         # 設定の自動大文字
        self.learning = learning
        self.now = 1_000_000
        self.out = text
        self.en_word = ""
        self.shift = "OFF"
        self.shift_auto = False
        self.auto_cap_suppressed = False
        self.auto_spaced = False
        self.symbol_mode = "NORMAL"
        self.last_commit: Optional[Tuple[str, str]] = None
        self.enter_count = 0
        self.candidates: List[str] = []
        self.update_auto_shift()

    # ---- 表示
    def composing(self) -> str:
        return self.en_word

    def text(self) -> str:
        """入力欄に見えている文字列（確定済み + 合成中）。"""
        return self.out + self.en_word

    def status(self) -> str:
        s = {"ONCE": "⇧", "LOCK": "⇧⇧", "OFF": ""}[self.shift]
        if self.symbol_mode == "PUNCTUATION":
            s = (s + " •") if s else "•"
        return s

    # ---- 自動大文字
    def _cursor_wants_caps(self) -> bool:
        if not self.caps_field:
            return False
        if self.en_word:
            return False
        return sentence_start(self.out)

    def update_auto_shift(self) -> None:
        if self.shift == "LOCK":
            return
        if self.shift == "ONCE" and not self.shift_auto:
            return
        want = self.auto_cap and not self.auto_cap_suppressed and self._cursor_wants_caps()
        if want == (self.shift == "ONCE"):
            return
        self.shift = "ONCE" if want else "OFF"
        self.shift_auto = want

    # ---- 確定
    def _commit_final(self, text: str, undo_reading: Optional[str] = None) -> None:
        self.out += text
        self.last_commit = (text, undo_reading) if undo_reading else None
        self.auto_cap_suppressed = False
        self.update_auto_shift()

    def _record(self, word: str) -> None:
        if self.learning:
            self.predictor.record(word, self.now)

    def _suggest(self) -> None:
        if not self.en_word or not self.predict_here:
            self.candidates = []
        else:
            self.candidates = self.predictor.predict(self.en_word, self.now)

    def _commit_word(self, suffix: str) -> None:
        word = self.en_word
        self.en_word = ""
        self.candidates = []
        self._record(word)
        self._commit_final(word + suffix)

    def _flush(self) -> None:
        self.candidates = []
        if self.en_word:
            self._commit_word("")

    # ---- ストローク
    def stroke(self, symbol: str) -> "EnglishIME":
        self.now += 1000
        if symbol != BACKSPACE:
            self.last_commit = None
        if symbol == TAP:
            if self.symbol_mode == "NORMAL":
                self.symbol_mode = "PUNCTUATION"
            else:
                self.symbol_mode = "NORMAL"
                self._emit(".")
            return self
        if self.symbol_mode != "NORMAL":
            self.symbol_mode = "NORMAL"
            if symbol != BACKSPACE:
                self._emit(symbol)
            return self
        if symbol == SHIFT:
            self._shift_stroke()
        elif symbol == SPACE:
            self._space()
        elif symbol == BACKSPACE:
            self._backspace()
        elif symbol == RETURN:
            self._return()
        else:
            self._character(symbol)
        return self

    def write(self, letters: str) -> "EnglishIME":
        """英字・数字・空白をまとめて書く（空白はスペースのストローク）。"""
        for ch in letters:
            self.stroke(SPACE if ch == " " else ch)
        return self

    def punct(self, symbol: str) -> "EnglishIME":
        """タップ（Punctuation Shift）に続けて記号を書く。"""
        self.stroke(TAP)
        if symbol == ".":
            return self.stroke(TAP)
        return self.stroke(symbol)

    def _shift_stroke(self) -> None:
        if self.shift_auto and self.shift == "ONCE":
            self.shift = "OFF"
            self.auto_cap_suppressed = True
        else:
            self.shift = {"OFF": "ONCE", "ONCE": "LOCK", "LOCK": "OFF"}[self.shift]
        self.shift_auto = False

    def _character(self, symbol: str) -> None:
        if self.predict_here and len(symbol) == 1 and symbol.isalpha():
            text = symbol if self.shift == "OFF" else symbol.upper()
            self.auto_spaced = False
            self.auto_cap_suppressed = False
            self.en_word += text
            if self.shift == "ONCE":
                self.shift = "OFF"
                self.shift_auto = False
            self._suggest()
            self.update_auto_shift()
            return
        self._flush()
        self.auto_spaced = False
        self.auto_cap_suppressed = False
        text = symbol if self.shift == "OFF" else symbol.upper()
        if self.shift == "ONCE":
            self.shift = "OFF"
            self.shift_auto = False
        self._commit_final(text)

    def _emit(self, symbol: str) -> None:
        if symbol == Text.APOSTROPHE and self.en_word:
            self.en_word += symbol
            self._suggest()
            return
        tight = self.auto_spaced and len(symbol) == 1 and symbol in Text.TIGHT_PUNCTUATION
        self.auto_spaced = False
        self._flush()
        if tight and self.out.endswith(" "):
            self.out = self.out[:-1]
            self._commit_final(symbol + " ")
            self.auto_spaced = True
            return
        self._commit_final(symbol)

    def _space(self) -> None:
        if self.en_word:
            self._commit_word(" ")
            return
        if self.auto_spaced:
            self.auto_spaced = False
            return
        self._commit_final(" ")

    def _backspace(self) -> None:
        self.auto_spaced = False
        self.auto_cap_suppressed = False
        if not self.en_word and self.last_commit is not None:
            surface, reading = self.last_commit
            self.last_commit = None
            if self.out.endswith(surface):
                self.out = self.out[:len(self.out) - len(surface)]
                self.en_word = reading
                self._suggest()
                self.update_auto_shift()
                return
        if self.en_word:
            self.en_word = self.en_word[:-1]
            self._suggest()
            self.update_auto_shift()
            return
        self.out = self.out[:-1]
        self.update_auto_shift()

    def _return(self) -> None:
        self.auto_spaced = False
        self._flush()
        self.last_commit = None
        self.enter_count += 1

    # ---- ボタン・候補バー
    def pick(self, index: int = 0) -> "EnglishIME":
        """候補バーの候補をタップする。"""
        word = self.candidates[index]
        typed = self.en_word
        self.en_word = ""
        self.candidates = []
        self._record(word)
        self._commit_final(word + " ", typed)
        self.auto_spaced = True
        return self

    def delete_word(self) -> "EnglishIME":
        """左上のボタン（単語削除）。"""
        self.last_commit = None
        self.auto_spaced = False
        self.auto_cap_suppressed = False
        if self.en_word:
            self.en_word = ""
            self._suggest()
            self.update_auto_shift()
            return self
        n = Text.word_delete_length(self.out[-WORD_DELETE_LOOKBACK:])
        if n > 0:
            self.out = self.out[:len(self.out) - n]
        self.update_auto_shift()
        return self


WORD_DELETE_LOOKBACK = int_const(SRC["UniStrokeIME"], "WORD_DELETE_LOOKBACK")


# ------------------------------------------------------------------ 言語の決定

def resolve_language(setting: str, system_language: str) -> str:
    """AppLanguage.resolve の写し。"""
    if setting in ("ja", "en"):
        return setting
    return "ja" if system_language == "ja" else "en"
