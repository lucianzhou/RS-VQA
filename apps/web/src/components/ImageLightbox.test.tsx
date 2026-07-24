import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ImageLightbox } from "./ImageLightbox";

describe("ImageLightbox", () => {
  it("keeps the image in an accessible in-page dialog and closes with Escape", async () => {
    const onOpenChange = vi.fn();
    const interaction = userEvent.setup();
    render(
      <ImageLightbox
        open
        src="/api/v1/images/image-1/content"
        alt="遥感图像 forest.jpg"
        title="forest.jpg"
        meta="512 × 512 · 91 KiB"
        onOpenChange={onOpenChange}
      />,
    );

    expect(screen.getByRole("dialog", { name: "forest.jpg" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "遥感图像 forest.jpg" })).toHaveAttribute(
      "src",
      "/api/v1/images/image-1/content",
    );
    expect(screen.getByRole("link", { name: /下载原图/ })).not.toHaveAttribute("target", "_blank");

    await interaction.keyboard("{Escape}");
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });
});
