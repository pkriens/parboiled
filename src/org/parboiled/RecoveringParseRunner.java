/*
 * Copyright (C) 2009-2010 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled;

import com.google.common.base.Preconditions;
import org.jetbrains.annotations.NotNull;
import org.parboiled.errors.InvalidInputError;
import org.parboiled.matchers.Matcher;
import org.parboiled.matchers.SequenceMatcher;
import org.parboiled.matchers.TestMatcher;
import org.parboiled.support.*;

import java.util.List;

/**
 * A {@link ParseRunner} implementation that is able to recover from {@link InvalidInputError}s in the input and therefore
 * report more than just the first {@link InvalidInputError} if the input does not conform to the rule grammar.
 * Error recovery is done by attempting to either delete an error character or insert a potentially missing character,
 * whereby this implementation is able to determine itself which of these options is the best strategy.
 * If the parse error cannot be overcome by either deleting or inserting one character a resynchronization rule is
 * determined and the parsing process resynchronized, so that parsing can still continue.
 * In this way the RecoveringParseRunner is able to completely parse most input texts. Only if the parser cannot even
 * start matching the root rule will it return an unmatched {@link ParsingResult}.
 * If the input is error free this {@link ParseRunner} implementation will only perform one parsing run, with the same
 * speed as the {@link BasicParseRunner}. However, if there are {@link InvalidInputError}s in the input potentially
 * many more runs are performed to properly report all errors and test the various recovery strategies.
 *
 * @param <V> the type of the value field of a parse tree node
 */
public class RecoveringParseRunner<V> extends BasicParseRunner<V> {

    private int errorIndex;
    private InvalidInputError<V> currentError;
    private MutableInputBuffer buffer;

    /**
     * Create a new RecoveringParseRunner instance with the given rule and input text and returns the result of
     * its {@link #run()} method invocation.
     *
     * @param rule  the parser rule to run
     * @param input the input text to run on
     * @return the ParsingResult for the parsing run
     */
    public static <V> ParsingResult<V> run(@NotNull Rule rule, @NotNull String input) {
        return new RecoveringParseRunner<V>(rule, input).run();
    }

    /**
     * Creates a new RecoveringParseRunner instance for the given rule and input text.
     *
     * @param rule  the parser rule
     * @param input the input text
     */
    public RecoveringParseRunner(@NotNull Rule rule, @NotNull String input) {
        super(rule, input);
    }

    @Override
    protected InputBuffer createInputBuffer(String input) {
        buffer = new MutableInputBuffer(new DefaultInputBuffer(input));
        return buffer;
    }

    @Override
    protected boolean runRootContext() {
        MatchHandler<V> handler = new BasicParseRunner.Handler<V>();
        if (runRootContext(handler)) {
            return true;
        }
        if (attemptRecordingMatch()) {
            throw new IllegalStateException(); // we failed before so we must fail again
        }
        do {
            performErrorReportingRun();
            if (!fixError(errorIndex)) {
                return false;
            }
        } while (errorIndex >= 0);
        return true;
    }

    protected boolean attemptRecordingMatch() {
        RecordingParseRunner.Handler<V> handler = new RecordingParseRunner.Handler<V>(getInnerHandler());
        boolean matched = runRootContext(handler);
        errorIndex = handler.getErrorIndex();
        return matched;
    }

    protected void performErrorReportingRun() {
        ReportingParseRunner.Handler<V> handler = new ReportingParseRunner.Handler<V>(errorIndex, getInnerHandler());
        runRootContext(handler);
        currentError = handler.getParseError();
    }

    protected MatchHandler<V> getInnerHandler() {
        return errorIndex >= 0 ? new Handler<V>(currentError) : new BasicParseRunner.Handler<V>();
    }

    protected boolean fixError(int fixIndex) {
        if (tryFixBySingleCharDeletion(fixIndex)) return true;
        int nextErrorAfterDeletion = errorIndex;

        Character bestInsertionCharacter = findBestSingleCharInsertion(fixIndex);
        if (bestInsertionCharacter == null) return true;
        int nextErrorAfterBestInsertion = errorIndex;

        int nextErrorAfterBestSingleCharFix = Math.max(nextErrorAfterDeletion, nextErrorAfterBestInsertion);
        if (nextErrorAfterBestSingleCharFix > fixIndex) {
            // we are able to overcome the error with a single char fix, so apply the best one found
            if (nextErrorAfterDeletion >= nextErrorAfterBestInsertion) {
                buffer.insertChar(fixIndex, Characters.DEL_ERROR);
                errorIndex = nextErrorAfterDeletion + 1;
                shiftCurrentErrorIndicesBy(1);
            } else {
                // we need to insert the characters in reverse order, since we insert twice on the same location
                buffer.insertChar(fixIndex, bestInsertionCharacter);
                buffer.insertChar(fixIndex, Characters.INS_ERROR);
                errorIndex = nextErrorAfterBestInsertion + 2;
                shiftCurrentErrorIndicesBy(2);
            }
        } else {
            // we can't fix the error with a single char fix, so fall back to resynchronization
            buffer.insertChar(fixIndex, Characters.RESYNC);
            shiftCurrentErrorIndicesBy(1);
            attemptRecordingMatch(); // find the next parse error
        }
        return true;
    }

    protected boolean tryFixBySingleCharDeletion(int fixIndex) {
        buffer.insertChar(fixIndex, Characters.DEL_ERROR);
        boolean nowErrorFree = attemptRecordingMatch();
        if (nowErrorFree) {
            shiftCurrentErrorIndicesBy(1);
        } else {
            buffer.undoCharInsertion(fixIndex);
            errorIndex = Math.max(errorIndex - 1, 0);
        }
        return nowErrorFree;
    }

    @SuppressWarnings({"ConstantConditions"})
    protected Character findBestSingleCharInsertion(int fixIndex) {
        GetAStarterCharVisitor<V> getAStarterCharVisitor = new GetAStarterCharVisitor<V>();
        int bestNextErrorIndex = -1;
        Character bestChar = null;
        for (MatcherPath<V> failedMatcherPath : currentError.getFailedMatchers()) {
            Character starterChar = failedMatcherPath.getHead().accept(getAStarterCharVisitor);
            Preconditions.checkState(starterChar != null); // we should only have single character matchers
            if (starterChar == Characters.EOI) {
                continue; // we should never conjure up an EOI character (that would be cheating :)
            }
            buffer.insertChar(fixIndex, starterChar);
            buffer.insertChar(fixIndex, Characters.INS_ERROR);
            if (attemptRecordingMatch()) {
                shiftCurrentErrorIndicesBy(2);
                return null; // success, exit immediately
            }
            buffer.undoCharInsertion(fixIndex);
            buffer.undoCharInsertion(fixIndex);
            errorIndex = Math.max(errorIndex - 2, 0);

            if (bestNextErrorIndex < errorIndex) {
                bestNextErrorIndex = errorIndex;
                bestChar = starterChar;
            }
        }
        errorIndex = bestNextErrorIndex;
        return bestChar;
    }

    private void shiftCurrentErrorIndicesBy(int delta) {
        currentError.setStartIndex(currentError.getStartIndex() + delta);
        currentError.setEndIndex(currentError.getEndIndex() + delta);
    }

    /**
     * A {@link MatchHandler} implementation that recognizes the special {@link Characters#RESYNC} character
     * to overcome {@link InvalidInputError}s at the respective error indices.
     *
     * @param <V> the type of the value field of a parse tree node
     */
    public static class Handler<V> implements MatchHandler<V> {
        private final IsSingleCharMatcherVisitor<V> isSingleCharMatcherVisitor = new IsSingleCharMatcherVisitor<V>();
        private final InvalidInputError<V> currentError;
        private int fringeIndex;
        private MatcherPath<V> lastMatchPath;

        /**
         * Creates a new Handler. If a non-null InvalidInputError is given the handler will set its endIndex
         * to the correct index if the error corresponds to an error that can only be overcome by resynchronizing.
         *
         * @param currentError an optional InvalidInputError whose endIndex is to set during resyncing
         */
        public Handler(InvalidInputError<V> currentError) {
            this.currentError = currentError;
        }

        public boolean matchRoot(MatcherContext<V> rootContext) {
            return rootContext.runMatcher();
        }

        public boolean match(MatcherContext<V> context) {
            Matcher<V> matcher = context.getMatcher();
            if (matcher.accept(isSingleCharMatcherVisitor)) {
                if (prepareErrorLocation(context) && matcher.match(context)) {
                    if (fringeIndex < context.getCurrentIndex()) {
                        fringeIndex = context.getCurrentIndex();
                        lastMatchPath = context.getPath();
                    }
                    return true;
                }
                return false;
            }

            if (matcher.match(context)) {
                return true;
            }

            // if we didn't match we might have to resynchronize, however we only resynchronize
            // if we are at a RESYNC location and the matcher is a SequenceMatchers that has already
            // matched at least one character and that is a parent of the last match
            return context.getInputBuffer().charAt(fringeIndex) == Characters.RESYNC &&
                    qualifiesForResync(context, matcher) &&
                    resynchronize(context);
        }

        @SuppressWarnings({"SimplifiableIfStatement"})
        private boolean qualifiesForResync(MatcherContext<V> context, Matcher<V> matcher) {
            if (matcher instanceof SequenceMatcher && context.getCurrentIndex() > context.getStartIndex() &&
                    context.getPath().isPrefixOf(lastMatchPath)) {
                return true;
            }
            return context.getParent() == null; // always resync on the root if there is nothing else
        }

        protected boolean prepareErrorLocation(MatcherContext<V> context) {
            switch (context.getCurrentChar()) {
                case Characters.DEL_ERROR:
                    return willMatchDelError(context);
                case Characters.INS_ERROR:
                    return willMatchInsError(context);
            }
            return true;
        }

        protected boolean willMatchDelError(MatcherContext<V> context) {
            int preSkipIndex = context.getCurrentIndex();
            context.advanceIndex(); // skip del marker char
            context.advanceIndex(); // skip illegal char
            if (!runTestMatch(context)) {
                // if we wouldn't succeed with the match do not swallow the ERROR char & Co
                context.setCurrentIndex(preSkipIndex);
                return false;
            }
            context.setStartIndex(context.getCurrentIndex());
            context.clearNodeSuppression();
            if (context.getParent() != null) context.getParent().markError();
            return true;
        }

        protected boolean willMatchInsError(MatcherContext<V> context) {
            int preSkipIndex = context.getCurrentIndex();
            context.advanceIndex(); // skip ins marker char
            if (!runTestMatch(context)) {
                // if we wouldn't succeed with the match do not swallow the ERROR char
                context.setCurrentIndex(preSkipIndex);
                return false;
            }
            context.setStartIndex(context.getCurrentIndex());
            context.clearNodeSuppression();
            context.markError();
            return true;
        }

        protected boolean runTestMatch(MatcherContext<V> context) {
            TestMatcher<V> testMatcher = new TestMatcher<V>(context.getMatcher());
            return testMatcher.getSubContext(context).runMatcher();
        }

        protected boolean resynchronize(MatcherContext<V> context) {
            context.clearNodeSuppression();
            context.markError();

            // create a node for the failed Sequence, taking ownership of all sub nodes created so far
            context.createNode();

            // skip over all characters that are not legal followers of the failed Sequence
            context.advanceIndex(); // gobble RESYNC marker
            fringeIndex++;
            List<Matcher<V>> followMatchers = new FollowMatchersVisitor<V>().getFollowMatchers(context);
            int endIndex = gobbleIllegalCharacters(context, followMatchers);

            if (currentError != null && currentError.getStartIndex() == fringeIndex && endIndex - fringeIndex > 1) {
                currentError.setEndIndex(endIndex);
            }

            return true;
        }

        protected int gobbleIllegalCharacters(MatcherContext<V> context, List<Matcher<V>> followMatchers) {
            while_loop:
            while (true) {
                char currentChar = context.getCurrentChar();
                if (currentChar == Characters.EOI) break;
                for (Matcher<V> followMatcher : followMatchers) {
                    if (followMatcher.accept(new IsStarterCharVisitor<V>(currentChar))) {
                        break while_loop;
                    }
                }
                context.advanceIndex();
            }
            return context.getCurrentIndex();
        }
    }

}