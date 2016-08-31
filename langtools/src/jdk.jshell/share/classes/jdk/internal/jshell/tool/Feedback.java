/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.jshell.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.stream.Collectors.joining;

/**
 * Feedback customization support
 *
 * @author Robert Field
 */
class Feedback {

    // Patern for substituted fields within a customized format string
    private static final Pattern FIELD_PATTERN = Pattern.compile("\\{(.*?)\\}");

    // Internal field name for truncation length
    private static final String TRUNCATION_FIELD = "<truncation>";

    // For encoding to Properties String
    private static final String RECORD_SEPARATOR = "\u241E";

    // Current mode -- initial value is placeholder during start-up
    private Mode mode = new Mode("");

    // Retained current mode -- for checks
    private Mode retainedCurrentMode = null;

    // Mapping of mode name to mode
    private final Map<String, Mode> modeMap = new HashMap<>();

    // Mapping of mode names to encoded retained mode
    private final Map<String, String> retainedMap = new HashMap<>();

    // Mapping selector enum names to enums
    private final Map<String, Selector<?>> selectorMap = new HashMap<>();

    private static final long ALWAYS = bits(FormatCase.all, FormatAction.all, FormatWhen.all,
            FormatResolve.all, FormatUnresolved.all, FormatErrors.all);
    private static final long ANY = 0L;

    public boolean shouldDisplayCommandFluff() {
        return mode.commandFluff;
    }

    public String getPre() {
        return mode.format("pre", ANY);
    }

    public String getPost() {
        return mode.format("post", ANY);
    }

    public String getErrorPre() {
        return mode.format("errorpre", ANY);
    }

    public String getErrorPost() {
        return mode.format("errorpost", ANY);
    }

    public String format(FormatCase fc, FormatAction fa, FormatWhen fw,
                    FormatResolve fr, FormatUnresolved fu, FormatErrors fe,
                    String name, String type, String value, String unresolved, List<String> errorLines) {
        return mode.format(fc, fa, fw, fr, fu, fe,
                name, type, value, unresolved, errorLines);
    }

    public String getPrompt(String nextId) {
        return mode.getPrompt(nextId);
    }

    public String getContinuationPrompt(String nextId) {
        return mode.getContinuationPrompt(nextId);
    }

    public boolean setFeedback(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).setFeedback();
    }

    public boolean setFormat(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).setFormat();
    }

    public boolean setTruncation(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).setTruncation();
    }

    public boolean setMode(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).setMode();
    }

    public boolean setPrompt(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).setPrompt();
    }

    public String retainFeedback(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).retainFeedback();
    }

    public String retainMode(MessageHandler messageHandler, ArgTokenizer at) {
        return new Setter(messageHandler, at).retainMode();
    }

    public boolean restoreEncodedModes(MessageHandler messageHandler, String encoded) {
        return new Setter(messageHandler, new ArgTokenizer("<init>", "")).restoreEncodedModes(encoded);
    }

    public void markModesReadOnly() {
        modeMap.values().stream()
                .forEach(m -> m.readOnly = true);
    }

    {
        for (FormatCase e : FormatCase.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
        for (FormatAction e : FormatAction.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
        for (FormatResolve e : FormatResolve.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
        for (FormatUnresolved e : FormatUnresolved.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
        for (FormatErrors e : FormatErrors.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
        for (FormatWhen e : FormatWhen.all)
            selectorMap.put(e.name().toLowerCase(Locale.US), e);
    }

    /**
     * Holds all the context of a mode mode
     */
    private static class Mode {

        // Name of mode
        final String name;

        // Display command verification/information
        boolean commandFluff;

        // Event cases: class, method, expression, ...
        final Map<String, List<Setting>> cases;

        boolean readOnly = false;

        String prompt = "\n-> ";
        String continuationPrompt = ">> ";

        static class Setting {
            final long enumBits;
            final String format;
            Setting(long enumBits, String format) {
                this.enumBits = enumBits;
                this.format = format;
            }
        }

        /**
         * Set up an empty mode.
         *
         * @param name
         * @param commandFluff True if should display command fluff messages
         */
        Mode(String name) {
            this.name = name;
            this.cases = new HashMap<>();
            add("name",       new Setting(ALWAYS, "%1$s"));
            add("type",       new Setting(ALWAYS, "%2$s"));
            add("value",      new Setting(ALWAYS, "%3$s"));
            add("unresolved", new Setting(ALWAYS, "%4$s"));
            add("errors",     new Setting(ALWAYS, "%5$s"));
            add("err",        new Setting(ALWAYS, "%6$s"));

            add("errorline",  new Setting(ALWAYS, "    {err}%n"));

            add("pre",        new Setting(ALWAYS, "|  "));
            add("post",       new Setting(ALWAYS, "%n"));
            add("errorpre",   new Setting(ALWAYS, "|  "));
            add("errorpost",  new Setting(ALWAYS, "%n"));
        }

        /**
         * Set up a copied mode.
         *
         * @param name
         * @param m Mode to copy, or null for no fresh
         */
        Mode(String name, Mode m) {
            this.name = name;
            this.commandFluff = m.commandFluff;
            this.prompt = m.prompt;
            this.continuationPrompt = m.continuationPrompt;
            this.cases = new HashMap<>();
            m.cases.entrySet().stream()
                    .forEach(fes -> fes.getValue()
                    .forEach(ing -> add(fes.getKey(), ing)));

        }

        /**
         * Set up a mode reconstituted from a preferences string.
         *
         * @param it the encoded Mode broken into String chunks, may contain
         * subsequent encoded modes
         */
        Mode(Iterator<String> it) {
            this.name = it.next();
            this.commandFluff = Boolean.parseBoolean(it.next());
            this.prompt = it.next();
            this.continuationPrompt = it.next();
            cases = new HashMap<>();
            String field;
            while (!(field = it.next()).equals("***")) {
                String open = it.next();
                assert open.equals("(");
                List<Setting> settings = new ArrayList<>();
                String bits;
                while (!(bits = it.next()).equals(")")) {
                    String format = it.next();
                    Setting ing = new Setting(Long.parseLong(bits), format);
                    settings.add(ing);
                }
                cases.put(field, settings);
            }
        }

        /**
         * Set if this mode displays informative/confirmational messages on
         * commands.
         *
         * @param fluff the value to set
         */
        void setCommandFluff(boolean fluff) {
            commandFluff = fluff;
        }

        /**
         * Encodes the mode into a String so it can be saved in Preferences.
         *
         * @return the string representation
         */
        String encode() {
            List<String> el = new ArrayList<>();
            el.add(name);
            el.add(String.valueOf(commandFluff));
            el.add(prompt);
            el.add(continuationPrompt);
            for (Entry<String, List<Setting>> es : cases.entrySet()) {
                el.add(es.getKey());
                el.add("(");
                for (Setting ing : es.getValue()) {
                    el.add(String.valueOf(ing.enumBits));
                    el.add(ing.format);
                }
                el.add(")");
            }
            el.add("***");
            return String.join(RECORD_SEPARATOR, el);
        }

        private boolean add(String field, Setting ing) {
            List<Setting> settings =  cases.computeIfAbsent(field, k -> new ArrayList<>());
            if (settings == null) {
                return false;
            }
            settings.add(ing);
            return true;
        }

        void set(String field,
                Collection<FormatCase> cc, Collection<FormatAction> ca, Collection<FormatWhen> cw,
                Collection<FormatResolve> cr, Collection<FormatUnresolved> cu, Collection<FormatErrors> ce,
                String format) {
            long bits = bits(cc, ca, cw, cr, cu, ce);
            set(field, bits, format);
        }

        void set(String field, long bits, String format) {
            add(field, new Setting(bits, format));
        }

        /**
         * Lookup format Replace fields with context specific formats.
         *
         * @return format string
         */
        String format(String field, long bits) {
            List<Setting> settings = cases.get(field);
            if (settings == null) {
                return ""; //TODO error?
            }
            String format = null;
            for (int i = settings.size() - 1; i >= 0; --i) {
                Setting ing = settings.get(i);
                long mask = ing.enumBits;
                if ((bits & mask) == bits) {
                    format = ing.format;
                    break;
                }
            }
            if (format == null || format.isEmpty()) {
                return "";
            }
            Matcher m = FIELD_PATTERN.matcher(format);
            StringBuffer sb = new StringBuffer(format.length());
            while (m.find()) {
                String fieldName = m.group(1);
                String sub = format(fieldName, bits);
                m.appendReplacement(sb, Matcher.quoteReplacement(sub));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        // Compute the display output given full context and values
        String format(FormatCase fc, FormatAction fa, FormatWhen fw,
                    FormatResolve fr, FormatUnresolved fu, FormatErrors fe,
                    String name, String type, String value, String unresolved, List<String> errorLines) {
            // Convert the context into a bit representation used as selectors for store field formats
            long bits = bits(fc, fa, fw, fr, fu, fe);
            String fname = name==null? "" : name;
            String ftype = type==null? "" : type;
            // Compute the representation of value
            String fvalue;
            if (value==null) {
                fvalue = "";
            } else {
                // Retrieve the truncation length
                String truncField = format(TRUNCATION_FIELD, bits);
                if (truncField.isEmpty()) {
                    // No truncation set, use whole value
                    fvalue = value;
                } else {
                    // Convert truncation length to int
                    // this is safe since it has been tested before it is set
                    int trunc = Integer.parseUnsignedInt(truncField);
                    if (value.length() > trunc) {
                        if (trunc <= 5) {
                            // Very short truncations have no room for "..."
                            fvalue = value.substring(0, trunc);
                        } else {
                            // Normal truncation, make total length equal truncation length
                            fvalue = value.substring(0, trunc - 4) + " ...";
                        }
                    } else {
                        // Within truncation length, use whole value
                        fvalue = value;
                    }
                }
            }
            String funresolved = unresolved==null? "" : unresolved;
            String errors = errorLines.stream()
                    .map(el -> String.format(
                            format("errorline", bits),
                            fname, ftype, fvalue, funresolved, "*cannot-use-errors-here*", el))
                    .collect(joining());
            return String.format(
                    format("display", bits),
                    fname, ftype, fvalue, funresolved, errors, "*cannot-use-err-here*");
        }

        void setPrompts(String prompt, String continuationPrompt) {
            this.prompt = prompt;
            this.continuationPrompt = continuationPrompt;
        }

        String getPrompt(String nextId) {
            return String.format(prompt, nextId);
        }

        String getContinuationPrompt(String nextId) {
            return String.format(continuationPrompt, nextId);
        }
    }

    // Representation of one instance of all the enum values as bits in a long
    private static long bits(FormatCase fc, FormatAction fa, FormatWhen fw,
            FormatResolve fr, FormatUnresolved fu, FormatErrors fe) {
        long res = 0L;
        res |= 1 << fc.ordinal();
        res <<= FormatAction.count;
        res |= 1 << fa.ordinal();
        res <<= FormatWhen.count;
        res |= 1 << fw.ordinal();
        res <<= FormatResolve.count;
        res |= 1 << fr.ordinal();
        res <<= FormatUnresolved.count;
        res |= 1 << fu.ordinal();
        res <<= FormatErrors.count;
        res |= 1 << fe.ordinal();
        return res;
    }

    // Representation of a space of enum values as or'edbits in a long
    private static long bits(Collection<FormatCase> cc, Collection<FormatAction> ca, Collection<FormatWhen> cw,
                Collection<FormatResolve> cr, Collection<FormatUnresolved> cu, Collection<FormatErrors> ce) {
        long res = 0L;
        for (FormatCase fc : cc)
            res |= 1 << fc.ordinal();
        res <<= FormatAction.count;
        for (FormatAction fa : ca)
            res |= 1 << fa.ordinal();
        res <<= FormatWhen.count;
        for (FormatWhen fw : cw)
            res |= 1 << fw.ordinal();
        res <<= FormatResolve.count;
        for (FormatResolve fr : cr)
            res |= 1 << fr.ordinal();
        res <<= FormatUnresolved.count;
        for (FormatUnresolved fu : cu)
            res |= 1 << fu.ordinal();
        res <<= FormatErrors.count;
        for (FormatErrors fe : ce)
            res |= 1 << fe.ordinal();
        return res;
    }

    interface Selector<E extends Enum<E> & Selector<E>> {
        SelectorCollector<E> collector(Setter.SelectorList sl);
        String doc();
    }

    /**
     * The event cases
     */
    public enum FormatCase implements Selector<FormatCase> {
        IMPORT("import declaration"),
        CLASS("class declaration"),
        INTERFACE("interface declaration"),
        ENUM("enum declaration"),
        ANNOTATION("annotation interface declaration"),
        METHOD("method declaration -- note: {type}==parameter-types"),
        VARDECL("variable declaration without init"),
        VARINIT("variable declaration with init"),
        EXPRESSION("expression -- note: {name}==scratch-variable-name"),
        VARVALUE("variable value expression"),
        ASSIGNMENT("assign variable"),
        STATEMENT("statement");
        String doc;
        static final EnumSet<FormatCase> all = EnumSet.allOf(FormatCase.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatCase> collector(Setter.SelectorList sl) {
            return sl.cases;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatCase(String doc) {
            this.doc = doc;
        }
    }

    /**
     * The event actions
     */
    public enum FormatAction implements Selector<FormatAction> {
        ADDED("snippet has been added"),
        MODIFIED("an existing snippet has been modified"),
        REPLACED("an existing snippet has been replaced with a new snippet"),
        OVERWROTE("an existing snippet has been overwritten"),
        DROPPED("snippet has been dropped"),
        USED("snippet was used when it cannot be");
        String doc;
        static final EnumSet<FormatAction> all = EnumSet.allOf(FormatAction.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatAction> collector(Setter.SelectorList sl) {
            return sl.actions;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatAction(String doc) {
            this.doc = doc;
        }
    }

    /**
     * When the event occurs: primary or update
     */
    public enum FormatWhen implements Selector<FormatWhen> {
        PRIMARY("the entered snippet"),
        UPDATE("an update to a dependent snippet");
        String doc;
        static final EnumSet<FormatWhen> all = EnumSet.allOf(FormatWhen.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatWhen> collector(Setter.SelectorList sl) {
            return sl.whens;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatWhen(String doc) {
            this.doc = doc;
        }
    }

    /**
     * Resolution problems
     */
    public enum FormatResolve implements Selector<FormatResolve> {
        OK("resolved correctly"),
        DEFINED("defined despite recoverably unresolved references"),
        NOTDEFINED("not defined because of recoverably unresolved references");
        String doc;
        static final EnumSet<FormatResolve> all = EnumSet.allOf(FormatResolve.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatResolve> collector(Setter.SelectorList sl) {
            return sl.resolves;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatResolve(String doc) {
            this.doc = doc;
        }
    }

    /**
     * Count of unresolved references
     */
    public enum FormatUnresolved implements Selector<FormatUnresolved> {
        UNRESOLVED0("no names are unresolved"),
        UNRESOLVED1("one name is unresolved"),
        UNRESOLVED2("two or more names are unresolved");
        String doc;
        static final EnumSet<FormatUnresolved> all = EnumSet.allOf(FormatUnresolved.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatUnresolved> collector(Setter.SelectorList sl) {
            return sl.unresolvedCounts;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatUnresolved(String doc) {
            this.doc = doc;
        }
    }

    /**
     * Count of unresolved references
     */
    public enum FormatErrors implements Selector<FormatErrors> {
        ERROR0("no errors"),
        ERROR1("one error"),
        ERROR2("two or more errors");
        String doc;
        static final EnumSet<FormatErrors> all = EnumSet.allOf(FormatErrors.class);
        static final int count = all.size();

        @Override
        public SelectorCollector<FormatErrors> collector(Setter.SelectorList sl) {
            return sl.errorCounts;
        }

        @Override
        public String doc() {
            return doc;
        }

        private FormatErrors(String doc) {
            this.doc = doc;
        }
    }

    class SelectorCollector<E extends Enum<E> & Selector<E>> {
        final EnumSet<E> all;
        EnumSet<E> set = null;
        SelectorCollector(EnumSet<E> all) {
            this.all = all;
        }
        void add(Object o) {
            @SuppressWarnings("unchecked")
            E e = (E) o;
            if (set == null) {
                set = EnumSet.of(e);
            } else {
                set.add(e);
            }
        }

        boolean isEmpty() {
            return set == null;
        }

        EnumSet<E> getSet() {
            return set == null
                    ? all
                    : set;
        }
    }

    // Class used to set custom eval output formats
    // For both /set format  -- Parse arguments, setting custom format, or printing error
    private class Setter {

        private final ArgTokenizer at;
        private final MessageHandler messageHandler;
        boolean valid = true;

        Setter(MessageHandler messageHandler, ArgTokenizer at) {
            this.messageHandler = messageHandler;
            this.at = at;
        }

        void fluff(String format, Object... args) {
            messageHandler.fluff(format, args);
        }

        void fluffmsg(String messageKey, Object... args) {
            messageHandler.fluffmsg(messageKey, args);
        }

        void errorat(String messageKey, Object... args) {
            Object[] a2 = Arrays.copyOf(args, args.length + 2);
            a2[args.length] = at.whole();
            messageHandler.errormsg(messageKey, a2);
        }

        // For /set prompt <mode> "<prompt>" "<continuation-prompt>"
        boolean setPrompt() {
            Mode m = nextMode();
            if (valid && m.readOnly) {
                errorat("jshell.err.not.valid.with.predefined.mode", m.name);
                valid = false;
            }
            String prompt = valid ? nextFormat() : null;
            String continuationPrompt = valid ? nextFormat() : null;
            if (valid) {
                m.setPrompts(prompt, continuationPrompt);
            } else {
                fluffmsg("jshell.msg.see", "/help /set prompt");
            }
            return valid;
        }

        /**
         * Set mode. Create, changed, or delete a feedback mode. For @{code /set
         * mode <mode> [<old-mode>] [-command|-quiet|-delete]}.
         *
         * @return true if successful
         */
        boolean setMode() {
            at.allowedOptions("-command", "-quiet", "-delete");
            String umode = nextModeIdentifier();
            Mode om = null;
            String omode = at.next();
            if (valid && omode != null) {
                om = toMode(omode);
            }
            checkOptionsAndRemainingInput();
            boolean commandOption = at.hasOption("-command");
            boolean quietOption = at.hasOption("-quiet");
            boolean deleteOption = at.hasOption("-delete");
            // Only one (or zero) of the options can be used
            if (valid && at.optionCount() > 1) {
                errorat("jshell.err.conflicting.options");
                valid = false;
            }
            if (valid) {
                Mode m = modeMap.get(umode);
                if (m != null && m.readOnly) {
                    // Cannot make changes to a the built-in modes
                    errorat("jshell.err.not.valid.with.predefined.mode", m.name);
                    valid = false;
                } else if (deleteOption) {
                    if (m == null) {
                        // Cannot delete a mode that does not exist
                        errorat("jshell.err.mode.unknown", umode);
                        valid = false;
                    } else if (mode.name.equals(m.name)) {
                        // Cannot delete the current mode out from under us
                        errorat("jshell.err.cannot.delete.current.mode", umode);
                        valid = false;
                    } else {
                        // Remove the mode
                        modeMap.remove(umode);
                    }
                } else {
                    if (om != null || m == null) {
                        // We are copying and existing mode and/or creating a
                        // brand-new mode -- in either case create from scratch
                        m = (om != null)
                                ? new Mode(umode, om)
                                : new Mode(umode);
                        modeMap.put(umode, m);
                        fluffmsg("jshell.msg.feedback.new.mode", m.name);
                        // Set the current mode by name, in case we just smashed
                        // the current mode
                        if (umode.equals(mode.name)) {
                            mode = modeMap.get(mode.name);
                        }
                    }
                    if (commandOption || quietOption || om == null) {
                        // set command fluff, if explicit, or wholly new
                        m.setCommandFluff(!quietOption);
                    }
                }
            }
            if (!valid) {
                fluffmsg("jshell.msg.see", "/help /set mode");
            }
            return valid;
        }

        // For /set feedback <mode>
        boolean setFeedback() {
            Mode m = nextMode();
            if (valid) {
                mode = m;
                fluffmsg("jshell.msg.feedback.mode", mode.name);
            } else {
                fluffmsg("jshell.msg.see", "/help /set feedback");
                printFeedbackModes();
            }
            return valid;
        }

        // For /set format <mode> "<format>" <selector>...
        boolean setFormat() {
            Mode m = nextMode();
            if (valid && m.readOnly) {
                errorat("jshell.err.not.valid.with.predefined.mode", m.name);
                valid = false;
            }
            String field = valid
                    ? toIdentifier(at.next(), "jshell.err.missing.field", "jshell.err.field.name")
                    : null;
            String format = valid ? nextFormat() : null;
            return installFormat(m, field, format, "/help /set format");
        }

        // For /set truncation <mode> <length> <selector>...
        boolean setTruncation() {
            Mode m = nextMode();
            if (valid && m.readOnly) {
                errorat("jshell.err.not.valid.with.predefined.mode", m.name);
                valid = false;
            }
            String length = at.next();
            if (length == null) {
                errorat("jshell.err.truncation.expected.length");
                valid = false;
            } else {
                try {
                    // Assure that integer format is correct
                    Integer.parseUnsignedInt(length);
                } catch (NumberFormatException ex) {
                    errorat("jshell.err.truncation.length.not.integer", length);
                    valid = false;
                }
            }
            // install length into an internal format field
            return installFormat(m, TRUNCATION_FIELD, length, "/help /set truncation");
        }

        String retainFeedback() {
            String umode = at.next();
            if (umode != null) {
                toModeIdentifier(umode);
                Mode m = valid ? toMode(umode) : null;
                if (valid && !m.readOnly && !retainedMap.containsKey(m.name)) {
                    errorat("jshell.err.retained.feedback.mode.must.be.retained.or.predefined");
                    valid = false;
                }
                if (valid) {
                    mode = m;
                    retainedCurrentMode = m;
                    fluffmsg("jshell.msg.feedback.mode", mode.name);
                } else {
                    fluffmsg("jshell.msg.see", "/help /retain feedback");
                    return null;
                }
            }
            return mode.name;
        }

        /**
         * Retain (or delete from retention) a previously set mode.
         *
         * @return all retained modes encoded into a String
         */
        String retainMode() {
            at.allowedOptions("-delete");
            String umode = nextModeIdentifier();
            // -delete is the only valid option, fail for anything else
            checkOptionsAndRemainingInput();
            boolean deleteOption = at.hasOption("-delete");
            // Lookup the mode
            Mode m;
            if (!valid) {
                m = null;
                // Skip this stuff, we have failed already
            } else if (deleteOption) {
                // If delete, allow for deleting, from retention, a mode that
                // has been locally deleted but is retained.
                // Also require the full name.
                m = modeMap.get(umode);
                if (m == null && !retainedMap.containsKey(umode)) {
                    errorat("jshell.err.mode.unknown", umode);
                    valid = false;
                }
            } else {
                // For retain do normal lookup and checking
                m = toMode(umode);
            }

            // Built-in modes cannot be retained or deleted
            if (valid && m != null && m.readOnly) {
                errorat("jshell.err.not.valid.with.predefined.mode", umode);
                valid = false;
            }
            if (valid) {
                if (deleteOption) {
                    if (mode.name.equals(umode)) {
                        // Cannot delete the current mode out from under us
                        errorat("jshell.err.cannot.delete.current.mode", umode);
                        valid = false;
                    } else if (retainedCurrentMode != null && retainedCurrentMode.name.equals(umode)) {
                        // Cannot delete the retained mode or re-start has error
                        errorat("jshell.err.cannot.delete.retained.mode", umode);
                        valid = false;
                    } else {
                        // Delete the mode
                        modeMap.remove(umode);
                        retainedMap.remove(umode);
                    }
                } else {
                    // Retain the current encoding
                    retainedMap.put(m.name, m.encode());
                }
            }
            if (valid) {
                // Join all the retained encodings
                return String.join(RECORD_SEPARATOR, retainedMap.values());
            } else {
                fluffmsg("jshell.msg.see", "/help /retain mode");
                return null;
            }
        }

        boolean restoreEncodedModes(String allEncoded) {
            try {
                // Iterate over each record in each encoded mode
                String[] ms = allEncoded.split(RECORD_SEPARATOR);
                Iterator<String> itr = Arrays.asList(ms).iterator();
                while (itr.hasNext()) {
                    // Reconstruct the encoded mode
                    Mode m = new Mode(itr);
                    modeMap.put(m.name, m);
                    // Continue to retain it a new retains occur
                    retainedMap.put(m.name, m.encode());
                }
                return true;
            } catch (Throwable exc) {
                // Catastrophic corruption -- clear map
                errorat("jshell.err.retained.mode.failure", exc);
                retainedMap.clear();
                return false;
            }
        }

        // install the format of a field under parsed selectors
        boolean installFormat(Mode m, String field, String format, String help) {
            String slRaw;
            List<SelectorList> slList = new ArrayList<>();
            while (valid && (slRaw = at.next()) != null) {
                SelectorList sl = new SelectorList();
                sl.parseSelectorList(slRaw);
                slList.add(sl);
            }
            if (valid) {
                if (slList.isEmpty()) {
                    // No selectors specified, then always the format
                    m.set(field, ALWAYS, format);
                } else {
                    // Set the format of the field for specified selector
                    slList.stream()
                            .forEach(sl -> m.set(field,
                                sl.cases.getSet(), sl.actions.getSet(), sl.whens.getSet(),
                                sl.resolves.getSet(), sl.unresolvedCounts.getSet(), sl.errorCounts.getSet(),
                                format));
                }
            } else {
                fluffmsg("jshell.msg.see", help);
            }
            return valid;
        }

        void checkOptionsAndRemainingInput() {
            if (!valid) {
                return;
            }
            String junk = at.remainder();
            if (!junk.isEmpty()) {
                errorat("jshell.err.unexpected.at.end", junk);
                valid = false;
            } else {
                String bad = at.badOptions();
                if (!bad.isEmpty()) {
                    errorat("jshell.err.unknown.option", bad);
                    valid = false;
                }
            }
        }

        /**
         * Check that the specified string is an identifier (Java identifier).
         * If null display the missing error. If it is not an identifier,
         * display the error.
         *
         * @param id the string to check, MUST be the most recently retrieved
         * token from 'at'.
         * @param missing the resource error to display if null
         * @param err the resource error to display if not an identifier
         * @return the identifier string, or null if null or not an identifier
         */
        String toIdentifier(String id, String missing, String err) {
            if (id == null) {
                errorat(missing);
                valid = false;
                return null;
            }
            if (at.isQuoted() ||
                    !id.codePoints().allMatch(cp -> Character.isJavaIdentifierPart(cp))) {
                errorat(err, id);
                valid = false;
                return null;
            }
            return id;
        }

        String toModeIdentifier(String id) {
            return toIdentifier(id, "jshell.err.missing.mode", "jshell.err.mode.name");
        }

        String nextModeIdentifier() {
            return toModeIdentifier(at.next());
        }

        Mode nextMode() {
            String umode = nextModeIdentifier();
            return toMode(umode);
        }

        Mode toMode(String umode) {
            if (!valid) {
                return null;
            }
            if (umode == null) {
                errorat("jshell.err.missing.mode");
                valid = false;
                return null;
            }
            Mode m = modeMap.get(umode);
            if (m != null) {
                return m;
            }
            // Failing an exact match, go searching
            Mode[] matches = modeMap.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(umode))
                    .map(e -> e.getValue())
                    .toArray(size -> new Mode[size]);
            if (matches.length == 1) {
                return matches[0];
            } else {
                valid = false;
                if (matches.length == 0) {
                    errorat("jshell.err.feedback.does.not.match.mode", umode);
                } else {
                    errorat("jshell.err.feedback.ambiguous.mode", umode);
                }
                printFeedbackModes();
                return null;
            }
        }

        void printFeedbackModes() {
            fluffmsg("jshell.msg.feedback.mode.following");
            modeMap.keySet().stream()
                    .forEach(mk -> fluff("   %s", mk));
        }

        // Test if the format string is correctly
        final String nextFormat() {
            String format = at.next();
            if (format == null) {
                errorat("jshell.err.feedback.expected.format");
                valid = false;
                return null;
            }
            if (!at.isQuoted()) {
                errorat("jshell.err.feedback.must.be.quoted", format);
                valid = false;
                return null;
            }
            return format;
        }

        class SelectorList {

            SelectorCollector<FormatCase> cases = new SelectorCollector<>(FormatCase.all);
            SelectorCollector<FormatAction> actions = new SelectorCollector<>(FormatAction.all);
            SelectorCollector<FormatWhen> whens = new SelectorCollector<>(FormatWhen.all);
            SelectorCollector<FormatResolve> resolves = new SelectorCollector<>(FormatResolve.all);
            SelectorCollector<FormatUnresolved> unresolvedCounts = new SelectorCollector<>(FormatUnresolved.all);
            SelectorCollector<FormatErrors> errorCounts = new SelectorCollector<>(FormatErrors.all);

            final void parseSelectorList(String sl) {
                for (String s : sl.split("-")) {
                    SelectorCollector<?> lastCollector = null;
                    for (String as : s.split(",")) {
                        if (!as.isEmpty()) {
                            Selector<?> sel = selectorMap.get(as);
                            if (sel == null) {
                                errorat("jshell.err.feedback.not.a.valid.selector", as, s);
                                valid = false;
                                return;
                            }
                            SelectorCollector<?> collector = sel.collector(this);
                            if (lastCollector == null) {
                                if (!collector.isEmpty()) {
                                    errorat("jshell.err.feedback.multiple.sections", as, s);
                                    valid = false;
                                    return;
                                }
                            } else if (collector != lastCollector) {
                                errorat("jshell.err.feedback.different.selector.kinds", as, s);
                                valid = false;
                                return;
                            }
                            collector.add(sel);
                            lastCollector = collector;
                        }
                    }
                }
            }
        }
    }
}
