"use client";

import { type RefObject } from "react";
import { gsap, registerGsap, useGSAP } from "@/lib/gsap";

registerGsap();

type MotionSetup = (opts: { reduce: boolean }) => void;

/** Section-scoped GSAP setup with prefers-reduced-motion via matchMedia. */
export function useSectionMotion(
  scope: RefObject<HTMLElement | null>,
  setup: MotionSetup,
) {
  useGSAP(
    () => {
      const mm = gsap.matchMedia();
      mm.add(
        {
          reduce: "(prefers-reduced-motion: reduce)",
          motion: "(prefers-reduced-motion: no-preference)",
        },
        (context) => {
          const reduce = Boolean(context.conditions?.reduce);
          setup({ reduce });
        },
      );
      return () => mm.revert();
    },
    { scope },
  );
}
