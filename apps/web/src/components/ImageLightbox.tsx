import * as Dialog from "@radix-ui/react-dialog";
import { AnimatePresence, motion } from "motion/react";
import { Download, X } from "lucide-react";

interface ImageLightboxProps {
  open: boolean;
  src: string;
  alt: string;
  title?: string;
  meta?: string;
  onOpenChange: (open: boolean) => void;
}

export function ImageLightbox({
  open,
  src,
  alt,
  title,
  meta,
  onOpenChange,
}: ImageLightboxProps) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open && (
          <Dialog.Portal forceMount>
            <Dialog.Overlay asChild forceMount>
              <motion.div
                className="lightbox-overlay"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
              />
            </Dialog.Overlay>
            <Dialog.Content asChild forceMount aria-describedby="image-lightbox-description">
              <motion.div
                className="lightbox-dialog"
                initial={{ opacity: 0, scale: 0.965, y: 16 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.98, y: 8 }}
                transition={{ type: "spring", stiffness: 420, damping: 34, mass: 0.8 }}
              >
                <Dialog.Title>{title || "遥感图像预览"}</Dialog.Title>
                <Dialog.Description id="image-lightbox-description">
                  {meta || "在当前页面查看原始图像；按 Escape 或关闭按钮返回。"}
                </Dialog.Description>
                <div className="lightbox-stage">
                  <motion.img
                    src={src}
                    alt={alt}
                    initial={{ opacity: 0.4, scale: 0.99 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ duration: 0.22 }}
                  />
                </div>
                <div className="lightbox-toolbar">
                  <div>
                    <strong>{title || "遥感图像"}</strong>
                    {meta && <span>{meta}</span>}
                  </div>
                  <a className="quiet-button" href={src} download={title}>
                    <Download size={15} />下载原图
                  </a>
                </div>
                <Dialog.Close asChild>
                  <button className="lightbox-close" type="button" aria-label="关闭大图预览">
                    <X size={20} />
                  </button>
                </Dialog.Close>
              </motion.div>
            </Dialog.Content>
          </Dialog.Portal>
        )}
      </AnimatePresence>
    </Dialog.Root>
  );
}
