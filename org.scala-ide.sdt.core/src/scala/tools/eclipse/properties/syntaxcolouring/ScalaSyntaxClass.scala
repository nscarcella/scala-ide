package scala.tools.eclipse.properties.syntaxcolouring

import scala.tools.eclipse.ui.DisplayThread
import scala.tools.eclipse.util.EclipseUtils.PimpedPreferenceStore

import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.text.TextAttribute
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.graphics.Color

case class ScalaSyntaxClass(displayName: String, baseName: String, canBeDisabled: Boolean = false, hasForegroundColour: Boolean = true) {

  import ScalaSyntaxClasses._

  def enabledKey = baseName + ENABLED_SUFFIX
  def foregroundColourKey = baseName + FOREGROUND_COLOUR_SUFFIX
  def backgroundColourKey = baseName + BACKGROUND_COLOUR_SUFFIX
  def backgroundColourEnabledKey = baseName + BACKGROUND_COLOUR_ENABLED_SUFFIX
  def boldKey = baseName + BOLD_SUFFIX
  def italicKey = baseName + ITALIC_SUFFIX
  def underlineKey = baseName + UNDERLINE_SUFFIX

  /** Secondary constructor for backward compatibility with 3.x.
   *  TODO remove once 3.x compatibility is discarded
   */
  def this(_displayName: String, _baseName: String, _canBeDisabled: Boolean) =
    this(_displayName, _baseName, _canBeDisabled, true)

  def getTextAttribute(preferenceStore: IPreferenceStore): TextAttribute = {
    val styleInfo = getStyleInfo(preferenceStore)
    val style: Int = fullStyle(styleInfo)
    new TextAttribute(styleInfo.foregroundOpt.orNull, styleInfo.backgroundOpt.orNull, style)
  }

  def getStyleRange(preferenceStore: IPreferenceStore): StyleRange = {
    val styleRange = new StyleRange
    populateStyleRange(styleRange, preferenceStore)
    styleRange
  }

  def populateStyleRange(styleRange: StyleRange, preferenceStore: IPreferenceStore): Unit =
    if (preferenceStore.getBoolean(enabledKey)) {
      val StyleInfo(_, foregroundColour, backgroundColourOpt, bold, italic, underline) = getStyleInfo(preferenceStore)
      val style = basicStyle(bold, italic)
      styleRange.fontStyle = style
      styleRange.foreground = foregroundColour.orNull
      styleRange.background = backgroundColourOpt.orNull
      styleRange.underline = underline
      styleRange.underlineColor = styleRange.foreground
    }

  case class StyleInfo(enabled: Boolean, foregroundOpt: Option[Color], backgroundOpt: Option[Color], bold: Boolean, italic: Boolean, underline: Boolean)

  def getStyleInfo(preferenceStore: IPreferenceStore): StyleInfo = {
    val colourManager = JavaPlugin.getDefault.getJavaTextTools.getColorManager

    val foregroundColorPref = preferenceStore getColor foregroundColourKey
    var foregroundColorOpt: Option[Color] = None
    var backgroundOpt: Option[Color] = None

    // FIXME: Blocking on the UI thread is bad. I'm pretty sure we can avoid this, but some refactoring is in needed. Basically, the
    //        different SyntaxClasses should be created by the editor right after checking if semantic highlighting is enabled, that
    //        way you know you are running inside the UI Thread. Re #1001489.
    DisplayThread.syncExec {
      if (hasForegroundColour)
        foregroundColorOpt = Option(colourManager.getColor(foregroundColorPref))
      if (preferenceStore getBoolean backgroundColourEnabledKey)
        backgroundOpt = Option(colourManager.getColor(preferenceStore getColor backgroundColourKey))
    }

    StyleInfo(
      preferenceStore getBoolean enabledKey,
      foregroundColorOpt,
      backgroundOpt,
      preferenceStore getBoolean boldKey,
      preferenceStore getBoolean italicKey,
      preferenceStore getBoolean underlineKey)
  }

  private def basicStyle(bold: Boolean, italic: Boolean): Int = {
    var style = SWT.NORMAL
    if (bold) style |= SWT.BOLD
    if (italic) style |= SWT.ITALIC
    style
  }

  private def fullStyle(styleInfo: StyleInfo): Int = {
    val StyleInfo(_, _, _, bold, italic, underline) = styleInfo
    var style = basicStyle(bold, italic)
    if (underline) style |= TextAttribute.UNDERLINE
    style
  }

}

object ScalaSyntaxClass {
  def unapply(a: AnyRef): Option[(String, String, Boolean)] = a match {
    case syntaxClass: ScalaSyntaxClass =>
      Some((syntaxClass.displayName, syntaxClass.baseName, syntaxClass.canBeDisabled))
    case _ =>
      None
  }
}
