/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package build.tools.cldrconverter;

import java.io.File;
import java.io.IOException;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Handles parsing of files in Locale Data Markup Language and produces a map
 * that uses the keys and values of JRE locale data.
 */
class LDMLParseHandler extends AbstractLDMLHandler<Object> {
    private String defaultNumberingSystem;
    private String currentNumberingSystem = "";
    private CalendarType currentCalendarType;
    private String zoneNameStyle; // "long" or "short" for time zone names
    private String zonePrefix;
    private final String id;
    private String currentContext = ""; // "format"/"stand-alone"
    private String currentWidth = ""; // "wide"/"narrow"/"abbreviated"

    LDMLParseHandler(String id) {
        this.id = id;
    }

    @Override
    public InputSource resolveEntity(String publicID, String systemID) throws IOException, SAXException {
        // avoid HTTP traffic to unicode.org
        if (systemID.startsWith(CLDRConverter.LDML_DTD_SYSTEM_ID)) {
            return new InputSource((new File(CLDRConverter.LOCAL_LDML_DTD)).toURI().toString());
        }
        return null;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        switch (qName) {
        //
        // Generic information
        //
        case "identity":
            // ignore this element - it has language and territory elements that aren't locale data
            pushIgnoredContainer(qName);
            break;
        case "type":
            if ("calendar".equals(attributes.getValue("key"))) {
                pushStringEntry(qName, attributes, CLDRConverter.CALENDAR_NAME_PREFIX + attributes.getValue("type"));
            } else {
                pushIgnoredContainer(qName);
            }
            break;

        case "language":
        case "script":
        case "territory":
        case "variant":
            // for LocaleNames
            // copy string
            pushStringEntry(qName, attributes,
                CLDRConverter.LOCALE_NAME_PREFIX +
                (qName.equals("variant") ? "%%" : "") +
                attributes.getValue("type"));
            break;

        //
        // Currency information
        //
        case "currency":
            // for CurrencyNames
            // stash away "type" value for nested <symbol>
            pushKeyContainer(qName, attributes, attributes.getValue("type"));
            break;
        case "symbol":
            // for CurrencyNames
            // need to get the key from the containing <currency> element
            pushStringEntry(qName, attributes, CLDRConverter.CURRENCY_SYMBOL_PREFIX
                                               + getContainerKey());
            break;

        // Calendar or currency
        case "displayName":
            {
                if (currentContainer.getqName().equals("field")) {
                    pushStringEntry(qName, attributes,
                            (currentCalendarType != null ? currentCalendarType.keyElementName() : "")
                            + "field." + getContainerKey());
                } else {
                    // for CurrencyNames
                    // need to get the key from the containing <currency> element
                    // ignore if is has "count" attribute
                    String containerKey = getContainerKey();
                    if (containerKey != null && attributes.getValue("count") == null) {
                        pushStringEntry(qName, attributes,
                                        CLDRConverter.CURRENCY_NAME_PREFIX
                                        + containerKey.toLowerCase(Locale.ROOT),
                                        attributes.getValue("type"));
                    } else {
                        pushIgnoredContainer(qName);
                    }
                }
            }
            break;

        //
        // Calendar information
        //
        case "calendar":
            {
                // mostly for FormatData (CalendarData items firstDay and minDays are also nested)
                // use only if it's supported by java.util.Calendar.
                String calendarName = attributes.getValue("type");
                currentCalendarType = CalendarType.forName(calendarName);
                if (currentCalendarType != null) {
                    pushContainer(qName, attributes);
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;
        case "fields":
            {
                pushContainer(qName, attributes);
            }
            break;
        case "field":
            {
                String type = attributes.getValue("type");
                switch (type) {
                case "era":
                case "year":
                case "month":
                case "week":
                case "weekday":
                case "dayperiod":
                case "hour":
                case "minute":
                case "second":
                case "zone":
                    pushKeyContainer(qName, attributes, type);
                    break;
                default:
                    pushIgnoredContainer(qName);
                    break;
                }
            }
            break;
        case "monthContext":
            {
                // for FormatData
                // need to keep stand-alone and format, to allow for inheritance in CLDR
                String type = attributes.getValue("type");
                if ("stand-alone".equals(type) || "format".equals(type)) {
                    currentContext = type;
                    pushKeyContainer(qName, attributes, type);
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;
        case "monthWidth":
            {
                // for FormatData
                // create string array for the two types that the JRE knows
                // keep info about the context type so we can sort out inheritance later
                if (currentCalendarType == null) {
                    pushIgnoredContainer(qName);
                    break;
                }
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                currentWidth = attributes.getValue("type");
                switch (currentWidth) {
                case "wide":
                    pushStringArrayEntry(qName, attributes, prefix + "MonthNames/" + getContainerKey(), 13);
                    break;
                case "abbreviated":
                    pushStringArrayEntry(qName, attributes, prefix + "MonthAbbreviations/" + getContainerKey(), 13);
                    break;
                case "narrow":
                    pushStringArrayEntry(qName, attributes, prefix + "MonthNarrows/" + getContainerKey(), 13);
                    break;
                default:
                    pushIgnoredContainer(qName);
                    break;
                }
            }
            break;
        case "month":
            // for FormatData
            // add to string array entry of monthWidth element
            pushStringArrayElement(qName, attributes, Integer.parseInt(attributes.getValue("type")) - 1);
            break;
        case "dayContext":
            {
                // for FormatData
                // need to keep stand-alone and format, to allow for multiple inheritance in CLDR
                String type = attributes.getValue("type");
                if ("stand-alone".equals(type) || "format".equals(type)) {
                    currentContext = type;
                    pushKeyContainer(qName, attributes, type);
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;
        case "dayWidth":
            {
                // for FormatData
                // create string array for the two types that the JRE knows
                // keep info about the context type so we can sort out inheritance later
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                currentWidth = attributes.getValue("type");
                switch (currentWidth) {
                case "wide":
                    pushStringArrayEntry(qName, attributes, prefix + "DayNames/" + getContainerKey(), 7);
                    break;
                case "abbreviated":
                    pushStringArrayEntry(qName, attributes, prefix + "DayAbbreviations/" + getContainerKey(), 7);
                    break;
                case "narrow":
                    pushStringArrayEntry(qName, attributes, prefix + "DayNarrows/" + getContainerKey(), 7);
                    break;
                default:
                    pushIgnoredContainer(qName);
                    break;
                }
            }
            break;
        case "day":
            // for FormatData
            // add to string array entry of monthWidth element
            pushStringArrayElement(qName, attributes, Integer.parseInt(DAY_OF_WEEK_MAP.get(attributes.getValue("type"))) - 1);
            break;
        case "dayPeriodContext":
            // for FormatData
            // need to keep stand-alone and format, to allow for multiple inheritance in CLDR
            // for FormatData
            // need to keep stand-alone and format, to allow for multiple inheritance in CLDR
            {
                String type = attributes.getValue("type");
                if ("stand-alone".equals(type) || "format".equals(type)) {
                    currentContext = type;
                    pushKeyContainer(qName, attributes, type);
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;
        case "dayPeriodWidth":
            // for FormatData
            // create string array entry for am/pm. only keeping wide
            currentWidth = attributes.getValue("type");
            switch (currentWidth) {
            case "wide":
                pushStringArrayEntry(qName, attributes, "AmPmMarkers/" + getContainerKey(), 2);
                break;
            case "narrow":
                pushStringArrayEntry(qName, attributes, "narrow.AmPmMarkers/" + getContainerKey(), 2);
                break;
            default:
                pushIgnoredContainer(qName);
                break;
            }
            break;
        case "dayPeriod":
            // for FormatData
            // add to string array entry of AmPmMarkers element
            if (attributes.getValue("alt") == null) {
                switch (attributes.getValue("type")) {
                case "am":
                    pushStringArrayElement(qName, attributes, 0);
                    break;
                case "pm":
                    pushStringArrayElement(qName, attributes, 1);
                    break;
                default:
                    pushIgnoredContainer(qName);
                    break;
                }
            } else {
                // discard alt values
                pushIgnoredContainer(qName);
            }
            break;
        case "eraNames":
            // CLDR era names are inconsistent in terms of their lengths. For example,
            // the full names of Japanese imperial eras are eraAbbr, while the full names
            // of the Julian eras are eraNames.
            if (currentCalendarType == null) {
                assert currentContainer instanceof IgnoredContainer;
                pushIgnoredContainer(qName);
            } else {
                String key = currentCalendarType.keyElementName() + "long.Eras"; // for now
                pushStringArrayEntry(qName, attributes, key, currentCalendarType.getEraLength(qName));
            }
            break;
        case "eraAbbr":
            // for FormatData
            // create string array entry
            if (currentCalendarType == null) {
                assert currentContainer instanceof IgnoredContainer;
                pushIgnoredContainer(qName);
            } else {
                String key = currentCalendarType.keyElementName() + "Eras";
                pushStringArrayEntry(qName, attributes, key, currentCalendarType.getEraLength(qName));
            }
            break;
        case "eraNarrow":
            // mainly used for the Japanese imperial calendar
            if (currentCalendarType == null) {
                assert currentContainer instanceof IgnoredContainer;
                pushIgnoredContainer(qName);
            } else {
                String key = currentCalendarType.keyElementName() + "narrow.Eras";
                pushStringArrayEntry(qName, attributes, key, currentCalendarType.getEraLength(qName));
            }
            break;
        case "era":
            // for FormatData
            // add to string array entry of eraAbbr element
            if (currentCalendarType == null) {
                assert currentContainer instanceof IgnoredContainer;
                pushIgnoredContainer(qName);
            } else {
                int index = Integer.parseInt(attributes.getValue("type"));
                index = currentCalendarType.normalizeEraIndex(index);
                if (index >= 0) {
                    pushStringArrayElement(qName, attributes, index);
                } else {
                    pushIgnoredContainer(qName);
                }
                if (currentContainer.getParent() == null) {
                    throw new InternalError("currentContainer: null parent");
                }
            }
            break;
        case "quarterContext":
            {
                // for FormatData
                // need to keep stand-alone and format, to allow for inheritance in CLDR
                String type = attributes.getValue("type");
                if ("stand-alone".equals(type) || "format".equals(type)) {
                    currentContext = type;
                    pushKeyContainer(qName, attributes, type);
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;
        case "quarterWidth":
            {
                // for FormatData
                // keep info about the context type so we can sort out inheritance later
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                currentWidth = attributes.getValue("type");
                switch (currentWidth) {
                case "wide":
                    pushStringArrayEntry(qName, attributes, prefix + "QuarterNames/" + getContainerKey(), 4);
                    break;
                case "abbreviated":
                    pushStringArrayEntry(qName, attributes, prefix + "QuarterAbbreviations/" + getContainerKey(), 4);
                    break;
                case "narrow":
                    pushStringArrayEntry(qName, attributes, prefix + "QuarterNarrows/" + getContainerKey(), 4);
                    break;
                default:
                    pushIgnoredContainer(qName);
                    break;
                }
            }
            break;
        case "quarter":
            // for FormatData
            // add to string array entry of quarterWidth element
            pushStringArrayElement(qName, attributes, Integer.parseInt(attributes.getValue("type")) - 1);
            break;

        //
        // Time zone names
        //
        case "timeZoneNames":
            pushContainer(qName, attributes);
            break;
        case "zone":
            {
                String tzid = attributes.getValue("type"); // Olson tz id
                zonePrefix = CLDRConverter.TIMEZONE_ID_PREFIX;
                put(zonePrefix + tzid, new HashMap<String, String>());
                pushKeyContainer(qName, attributes, tzid);
            }
            break;
        case "metazone":
            {
                String zone = attributes.getValue("type"); // LDML meta zone id
                zonePrefix = CLDRConverter.METAZONE_ID_PREFIX;
                put(zonePrefix + zone, new HashMap<String, String>());
                pushKeyContainer(qName, attributes, zone);
            }
            break;
        case "long":
            zoneNameStyle = "long";
            pushContainer(qName, attributes);
            break;
        case "short":
            zoneNameStyle = "short";
            pushContainer(qName, attributes);
            break;
        case "generic":  // generic name
        case "standard": // standard time name
        case "daylight": // daylight saving (summer) time name
            pushStringEntry(qName, attributes, CLDRConverter.ZONE_NAME_PREFIX + qName + "." + zoneNameStyle);
            break;
        case "exemplarCity":  // not used in JDK
            pushIgnoredContainer(qName);
            break;

        //
        // Number format information
        //
        case "decimalFormatLength":
            if (attributes.getValue("type") == null) {
                // skipping type="short" data
                // for FormatData
                // copy string for later assembly into NumberPatterns
                pushStringEntry(qName, attributes, "NumberPatterns/decimal");
            } else {
                pushIgnoredContainer(qName);
            }
            break;
        case "currencyFormat":
            // for FormatData
            // copy string for later assembly into NumberPatterns
            if (attributes.getValue("type").equals("standard")) {
            pushStringEntry(qName, attributes, "NumberPatterns/currency");
            } else {
                pushIgnoredContainer(qName);
            }
            break;
        case "percentFormat":
            // for FormatData
            // copy string for later assembly into NumberPatterns
            if (attributes.getValue("type").equals("standard")) {
            pushStringEntry(qName, attributes, "NumberPatterns/percent");
            } else {
                pushIgnoredContainer(qName);
            }
            break;
        case "defaultNumberingSystem":
            // default numbering system if multiple numbering systems are used.
            pushStringEntry(qName, attributes, "DefaultNumberingSystem");
            break;
        case "symbols":
            // for FormatData
            // look up numberingSystems
            symbols: {
                String script = attributes.getValue("numberSystem");
                if (script == null) {
                    // Has no script. Just ignore.
                    pushIgnoredContainer(qName);
                    break;
                }

                // Use keys as <script>."NumberElements/<symbol>"
                currentNumberingSystem = script + ".";
                String digits = CLDRConverter.handlerNumbering.get(script);
                if (digits == null) {
                    throw new InternalError("null digits for " + script);
                }
                if (Character.isSurrogate(digits.charAt(0))) {
                    // DecimalFormatSymbols doesn't support supplementary characters as digit zero.
                    pushIgnoredContainer(qName);
                    break;
                }
                // in case digits are in the reversed order, reverse back the order.
                if (digits.charAt(0) > digits.charAt(digits.length() - 1)) {
                    StringBuilder sb = new StringBuilder(digits);
                    digits = sb.reverse().toString();
                }
                // Check if the order is sequential.
                char c0 = digits.charAt(0);
                for (int i = 1; i < digits.length(); i++) {
                    if (digits.charAt(i) != c0 + i) {
                        pushIgnoredContainer(qName);
                        break symbols;
                    }
                }
                @SuppressWarnings("unchecked")
                List<String> numberingScripts = (List<String>) get("numberingScripts");
                if (numberingScripts == null) {
                    numberingScripts = new ArrayList<>();
                    put("numberingScripts", numberingScripts);
                }
                numberingScripts.add(script);
                put(currentNumberingSystem + "NumberElements/zero", digits.substring(0, 1));
                pushContainer(qName, attributes);
            }
            break;
        case "decimal":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/decimal");
            break;
        case "group":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/group");
            break;
        case "list":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/list");
            break;
        case "percentSign":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/percent");
            break;
        case "nativeZeroDigit":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/zero");
            break;
        case "patternDigit":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/pattern");
            break;
        case "plusSign":
            // TODO: DecimalFormatSymbols doesn't support plusSign
            pushIgnoredContainer(qName);
            break;
        case "minusSign":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/minus");
            break;
        case "exponential":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/exponential");
            break;
        case "perMille":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/permille");
            break;
        case "infinity":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/infinity");
            break;
        case "nan":
            // for FormatData
            // copy string for later assembly into NumberElements
            pushStringEntry(qName, attributes, currentNumberingSystem + "NumberElements/nan");
            break;
        case "timeFormatLength":
            {
                // for FormatData
                // copy string for later assembly into DateTimePatterns
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                pushStringEntry(qName, attributes, prefix + "DateTimePatterns/" + attributes.getValue("type") + "-time");
            }
            break;
        case "dateFormatLength":
            {
                // for FormatData
                // copy string for later assembly into DateTimePatterns
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                pushStringEntry(qName, attributes, prefix + "DateTimePatterns/" + attributes.getValue("type") + "-date");
            }
            break;
        case "dateTimeFormatLength":
            {
                // for FormatData
                // copy string for later assembly into DateTimePatterns
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                pushStringEntry(qName, attributes, prefix + "DateTimePatterns/" + attributes.getValue("type") + "-dateTime");
            }
            break;
        case "localizedPatternChars":
            {
                // for FormatData
                // copy string for later adaptation to JRE use
                String prefix = (currentCalendarType == null) ? "" : currentCalendarType.keyElementName();
                pushStringEntry(qName, attributes, prefix + "DateTimePatternChars");
            }
            break;

        // "alias" for root
        case "alias":
            {
                if (id.equals("root") &&
                        !isIgnored(attributes) &&
                        currentCalendarType != null &&
                        !currentCalendarType.lname().startsWith("islamic-")) { // ignore Islamic variants
                    pushAliasEntry(qName, attributes, attributes.getValue("path"));
                } else {
                    pushIgnoredContainer(qName);
                }
            }
            break;

        default:
            // treat anything else as a container
            pushContainer(qName, attributes);
            break;
        }
    }

    private static final String[] CONTEXTS = {"stand-alone", "format"};
    private static final String[] WIDTHS = {"wide", "narrow", "abbreviated"};
    private static final String[] LENGTHS = {"full", "long", "medium", "short"};

    private void populateWidthAlias(String type, Set<String> keys) {
        for (String context : CONTEXTS) {
            for (String width : WIDTHS) {
                String keyName = toJDKKey(type+"Width", context, width);
                if (keyName.length() > 0) {
                    keys.add(keyName + "," + context + "," + width);
                }
            }
        }
    }

    private void populateFormatLengthAlias(String type, Set<String> keys) {
        for (String length: LENGTHS) {
            String keyName = toJDKKey(type+"FormatLength", currentContext, length);
            if (keyName.length() > 0) {
                keys.add(keyName + "," + currentContext + "," + length);
            }
        }
    }

    private Set<String> populateAliasKeys(String qName, String context, String width) {
        HashSet<String> ret = new HashSet<>();
        String keyName = qName;

        switch (qName) {
        case "monthWidth":
        case "dayWidth":
        case "quarterWidth":
        case "dayPeriodWidth":
        case "dateFormatLength":
        case "timeFormatLength":
        case "dateTimeFormatLength":
        case "eraNames":
        case "eraAbbr":
        case "eraNarrow":
            ret.add(toJDKKey(qName, context, width) + "," + context + "," + width);
            break;
        case "days":
            populateWidthAlias("day", ret);
            break;
        case "months":
            populateWidthAlias("month", ret);
            break;
        case "quarters":
            populateWidthAlias("quarter", ret);
            break;
        case "dayPeriods":
            populateWidthAlias("dayPeriod", ret);
            break;
        case "eras":
            ret.add(toJDKKey("eraNames", context, width) + "," + context + "," + width);
            ret.add(toJDKKey("eraAbbr", context, width) + "," + context + "," + width);
            ret.add(toJDKKey("eraNarrow", context, width) + "," + context + "," + width);
            break;
        case "dateFormats":
            populateFormatLengthAlias("date", ret);
            break;
        case "timeFormats":
            populateFormatLengthAlias("time", ret);
            break;
        default:
            break;
        }
        return ret;
    }

    private String translateWidthAlias(String qName, String context, String width) {
        String keyName = qName;
        String type = Character.toUpperCase(qName.charAt(0)) + qName.substring(1, qName.indexOf("Width"));

        switch (width) {
        case "wide":
            keyName = type + "Names/" + context;
            break;
        case "abbreviated":
            keyName = type + "Abbreviations/" + context;
            break;
        case "narrow":
            keyName = type + "Narrows/" + context;
            break;
        default:
            assert false;
        }

        return keyName;
    }

    private String toJDKKey(String containerqName, String context, String type) {
        String keyName = containerqName;

        switch (containerqName) {
        case "monthWidth":
        case "dayWidth":
        case "quarterWidth":
            keyName = translateWidthAlias(keyName, context, type);
            break;
        case "dayPeriodWidth":
            switch (type) {
            case "wide":
                keyName = "AmPmMarkers/" + context;
                break;
            case "narrow":
                keyName = "narrow.AmPmMarkers/" + context;
                break;
            case "abbreviated":
                keyName = "";
                break;
            }
            break;
        case "dateFormatLength":
        case "timeFormatLength":
        case "dateTimeFormatLength":
            keyName = "DateTimePatterns/" +
                type + "-" +
                keyName.substring(0, keyName.indexOf("FormatLength"));
            break;
        case "eraNames":
            keyName = "long.Eras";
            break;
        case "eraAbbr":
            keyName = "Eras";
            break;
        case "eraNarrow":
            keyName = "narrow.Eras";
            break;
        case "dateFormats":
        case "timeFormats":
        case "days":
        case "months":
        case "quarters":
        case "dayPeriods":
        case "eras":
            break;
        default:
            keyName = "";
            break;
        }

        return keyName;
    }

    private String getTarget(String path, String calType, String context, String width) {
        // Target qName
        int lastSlash = path.lastIndexOf('/');
        String qName = path.substring(lastSlash+1);
        int bracket = qName.indexOf('[');
        if (bracket != -1) {
            qName = qName.substring(0, bracket);
        }

        // calType
        String typeKey = "/calendar[@type='";
        int start = path.indexOf(typeKey);
        if (start != -1) {
            calType = path.substring(start+typeKey.length(), path.indexOf("']", start));
        }

        // context
        typeKey = "Context[@type='";
        start = path.indexOf(typeKey);
        if (start != -1) {
            context = (path.substring(start+typeKey.length(), path.indexOf("']", start)));
        }

        // width
        typeKey = "Width[@type='";
        start = path.indexOf(typeKey);
        if (start != -1) {
            width = path.substring(start+typeKey.length(), path.indexOf("']", start));
        }

        return calType + "." + toJDKKey(qName, context, width);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        assert qName.equals(currentContainer.getqName()) : "current=" + currentContainer.getqName() + ", param=" + qName;
        switch (qName) {
        case "calendar":
            assert !(currentContainer instanceof Entry);
            currentCalendarType = null;
            break;

        case "defaultNumberingSystem":
            if (currentContainer instanceof StringEntry) {
                defaultNumberingSystem = ((StringEntry) currentContainer).getValue();
                assert defaultNumberingSystem != null;
                put(((StringEntry) currentContainer).getKey(), defaultNumberingSystem);
            } else {
                defaultNumberingSystem = null;
            }
            break;

        case "timeZoneNames":
            zonePrefix = null;
            break;

        case "generic":
        case "standard":
        case "daylight":
            if (zonePrefix != null && (currentContainer instanceof Entry)) {
                @SuppressWarnings("unchecked")
                Map<String, String> valmap = (Map<String, String>) get(zonePrefix + getContainerKey());
                Entry<?> entry = (Entry<?>) currentContainer;
                valmap.put(entry.getKey(), (String) entry.getValue());
            }
            break;

        case "monthWidth":
        case "dayWidth":
        case "dayPeriodWidth":
        case "quarterWidth":
            currentWidth = "";
            putIfEntry();
            break;

        case "monthContext":
        case "dayContext":
        case "dayPeriodContext":
        case "quarterContext":
            currentContext = "";
            putIfEntry();
            break;

        default:
            putIfEntry();
        }
        currentContainer = currentContainer.getParent();
    }

    private void putIfEntry() {
        if (currentContainer instanceof AliasEntry) {
            Entry<?> entry = (Entry<?>) currentContainer;
            String containerqName = entry.getParent().getqName();
            Set<String> keyNames = populateAliasKeys(containerqName, currentContext, currentWidth);
            if (!keyNames.isEmpty()) {
                for (String keyName : keyNames) {
                    String[] tmp = keyName.split(",", 3);
                    String calType = currentCalendarType.lname();
                    String src = calType+"."+tmp[0];
                    String target = getTarget(
                                entry.getKey(),
                                calType,
                                tmp[1].length()>0 ? tmp[1] : currentContext,
                                tmp[2].length()>0 ? tmp[2] : currentWidth);
                    if (target.substring(target.lastIndexOf('.')+1).equals(containerqName)) {
                        target = target.substring(0, target.indexOf('.'))+"."+tmp[0];
                    }
                    CLDRConverter.aliases.put(src.replaceFirst("^gregorian.", ""),
                                              target.replaceFirst("^gregorian.", ""));
                }
            }
        } else if (currentContainer instanceof Entry) {
                Entry<?> entry = (Entry<?>) currentContainer;
                Object value = entry.getValue();
                if (value != null) {
                    String key = entry.getKey();
                    // Tweak for MonthNames for the root locale, Needed for
                    // SimpleDateFormat.format()/parse() roundtrip.
                    if (id.equals("root") && key.startsWith("MonthNames")) {
                        value = new DateFormatSymbols(Locale.US).getShortMonths();
                    }
                    put(entry.getKey(), value);
                }
            }
        }
    }
