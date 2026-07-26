import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

const SAFE_LINK_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);

function safeMarkdownUrl(url: string) {
  const value = url.trim();
  if (/^(?:\/(?!\/)|#|\?|\.\.?\/)/.test(value)) return value;

  try {
    const parsed = new URL(value);
    return SAFE_LINK_PROTOCOLS.has(parsed.protocol) ? value : "";
  } catch {
    return "";
  }
}

export function SafeMarkdown({
  content,
  className,
}: {
  content: string | null;
  className: string;
}) {
  return (
    <div className={`safe-markdown ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        disallowedElements={["img"]}
        urlTransform={safeMarkdownUrl}
        components={{
          a: ({ children: linkText, href, node: _node, ...props }) => href ? (
            <a
              {...props}
              href={href}
              rel={href.startsWith("http") ? "noreferrer noopener" : undefined}
              target={href.startsWith("http") ? "_blank" : undefined}
            >
              {linkText}
            </a>
          ) : <span>{linkText}</span>,
        }}
      >
        {content ?? ""}
      </ReactMarkdown>
    </div>
  );
}
