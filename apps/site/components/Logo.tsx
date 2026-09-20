import Image from "next/image";

export function LogoMark() {
  return (
    <Image
      src="/brand/logo-mark.png"
      alt="柚柚相册标志"
      width={72}
      height={78}
      priority
      className="h-9 w-auto"
    />
  );
}
