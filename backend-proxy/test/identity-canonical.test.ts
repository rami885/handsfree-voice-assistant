import { expect, it } from "vitest";
import { canonical } from "../src/identity";

it("canonicalizes mixed-case and non-ASCII keys by UTF-16 code units", () => {
    expect(canonical({ "é": 5, a: 3, Z: 2, "ä": 4, A: 1 })).toBe('{"A":1,"Z":2,"a":3,"ä":4,"é":5}');
    expect(canonical({ "\uE000": 2, "😀": 1 })).toBe('{"😀":1,"\uE000":2}');
  });
