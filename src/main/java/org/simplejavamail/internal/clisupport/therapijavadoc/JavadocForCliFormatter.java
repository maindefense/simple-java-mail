package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class JavadocForCliFormatter extends ContextualCommentFormatter {
	
	private final List<String> includedReferredDocumentation = new ArrayList<>();
	
	JavadocForCliFormatter() {
		super(0);
	}
	
	JavadocForCliFormatter(int nestingDepth) {
		super(nestingDepth);
	}
	
	@SuppressWarnings("StringConcatenationInLoop")
	@Override
	@Nonnull
	public String format(Comment comment) {
		String result = indent() + removeStructuralHTML(super.format(comment));
		final Pattern PATTERN_JAVADOC_TAG = compile("\\{@\\w+");
		final Pattern PATTERN_HTML_TAG = compile("</?[A-Za-z]+>");
		final Pattern PATTERN_TODO_FIXME = compile("//\\s*?(?:TODO|FIXME)"); // https://regex101.com/r/D79BMs/1
		
		assumeTrue(!PATTERN_JAVADOC_TAG.matcher(result).find() &&
						!PATTERN_HTML_TAG.matcher(result).find() &&
						!PATTERN_TODO_FIXME.matcher(result).find(),
				"Output not properly formatted for CLI usage: \n\t" + result + "\n\t-----------");
		for (String includedDocumentation : includedReferredDocumentation) {
			result += "\n\n" + indent(1) + includedDocumentation;
		}
		return result;
	}
	
	@Override
	protected String renderText(CommentText text) {
		return text.getValue()
				.replaceAll("\\s*\\n\\s*", " ") // removes newlines
				.replaceAll("\\s*<br\\s*/?>\\s*", "\n" + indent()) // replace <br/> with newlines
				.replaceAll("\\s*</?p\\s*>\\s*", "\n\n" + indent()) // replace <p> with sets of newlines
				.replaceAll("<strong>(.*?)</strong>", "@|bold $1|@")
				.replaceAll("<em>(.*?)</em>", "@|italic $1|@")
				.replaceAll("&gt;", ">")
				.replaceAll("&lt;", "<")
				.replaceAll("\\{@code (.*?)}", "@|green $1|@")
				.replaceAll("<code>(.*?)</code>", "@|green $1|@")
				.replaceAll("<a href=\"(.+?)\">(.+?)</a>", "$2 ($1)")
				.replaceAll("%s", "%%s");
	}
	
	@Override
	protected String renderCode(InlineTag e) {
		return String.format("@|green %s|@", e.getValue());
	}
	
	@Override
	protected String renderLink(InlineLink link) {
		final Method m = TherapiJavadocHelper.findMethodForLink(link.getLink());
		
		if (m != null) {
			final Class<?> apiNode = m.getDeclaringClass();
			final boolean isCliCompatible = BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(m);
			final String result = (isCliCompatible)
					? String.format("@|cyan %s|@", BuilderApiToPicocliCommandsMapper.determineCliOptionName(apiNode, m))
					: formatMethodReference("java-only method @|italic,faint ", m, "|@");
			return result + (checkIncludeReferredDocumentation(link, m, isCliCompatible) ? " (see below)" : "");
		} else {
			return String.format("@|green %s|@", link.getLink().getReferencedMemberName() != null
					? link.getLink().getReferencedMemberName()
					: link.getLink().getReferencedClassName());
		}
	}
	
	private boolean checkIncludeReferredDocumentation(InlineLink e, Method methodDelegate, boolean methodDelegateIsCliCompatible) {
		if (previousElementImpliesLinkedJavadocShouldBeIncluded(e)) {
			final Class<?> apiNode = methodDelegate.getDeclaringClass();
			final String inclusionHeader;
			if (methodDelegateIsCliCompatible) {
				inclusionHeader = String.format("@|underline -> |@@|underline,cyan %s|@:%n",
						BuilderApiToPicocliCommandsMapper.determineCliOptionName(apiNode, methodDelegate));
			} else {
				inclusionHeader = formatMethodReference("@|underline -> ", methodDelegate, "|@:%n");
			}
			includedReferredDocumentation.add(inclusionHeader + TherapiJavadocHelper.getJavadoc(methodDelegate, currentNestingDepth + 1));
			return true;
		}
		return false;
	}
	
	@Nonnull
	private String formatMethodReference(@Nonnull String prefix, @Nonnull Method m, @Nonnull String postfix) {
		return String.format("%s%s(%s)%s", prefix, m.getName(), describeMethodParameterTypes(m), postfix);
	}
	
	@Nonnull
	private static String describeMethodParameterTypes(Method deferredMethod) {
		final StringBuilder result = new StringBuilder();
		for (Class<?> parameterType : deferredMethod.getParameterTypes()) {
			result.append((result.length() == 0) ? "" : ", ").append(parameterType.getSimpleName());
		}
		return result.toString();
	}

	private boolean previousElementImpliesLinkedJavadocShouldBeIncluded(CommentElement e) {
		final CommentElement previousElement = getPreviousElement(e);
		if (previousElement instanceof InlineLink) {
			return previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement);
		} else if (previousElement instanceof CommentText) {
			final Pattern WORD_PATTERN = compile("\\w");
			final Pattern PATTERN_ALIAS_FOR = compile("(?i)Alias for:?");
			final Pattern PATTERN_DELEGATES_TO = compile("(?i)(?:delegates|delegating) to:?");
			
			final String trimmedToPlainText = removeStructuralHTML(((CommentText) previousElement).getValue()).trim();
			return PATTERN_DELEGATES_TO.matcher(trimmedToPlainText).find() ||
					PATTERN_ALIAS_FOR.matcher(trimmedToPlainText).find() ||
					(!WORD_PATTERN.matcher(trimmedToPlainText).find() && previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement));
		}
		return false;
	}
	
	@Override
	@Nonnull
	protected String renderValue(InlineValue e) {
		Object obj = TherapiJavadocHelper.resolveFieldForValue(e.getValue());
		if (obj != null) {
			return String.format("@|green %s|@", obj.toString());
		}
		throw new RuntimeException("{@value} cannot be resolved");
	}
	
	@Override
	protected String renderUnrecognizedTag(InlineTag e) {
		throw new RuntimeException(String.format("Found unsupported tag: %s=%s", e.getName(), e.getValue()));
	}
	
	@Nonnull
	private String removeStructuralHTML(@Nonnull String textWithHtml) {
		return textWithHtml
				.replaceAll("<li>", "\n  - " + indent())
				.replaceAll("</li>", "")
				.replaceAll("</?[ou]l>", "");
	}
}