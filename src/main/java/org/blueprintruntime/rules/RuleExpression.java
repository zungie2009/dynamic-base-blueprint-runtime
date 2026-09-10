package org.blueprintruntime.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A rule's calculation: one of the {@code supportedOperators} (ADD, SUBTRACT,
 * MULTIPLY, DIVIDE, SUM_RELATED) applied with a fixed decimal scale and rounding
 * mode. {@code ADD}/{@code SUBTRACT}/{@code MULTIPLY}/{@code DIVIDE} take two or more
 * ordered {@link Argument}s; {@code SUM_RELATED} instead names a source
 * {@code table.field}, a {@code where} join condition, and an {@code emptyResult}.
 */
public record RuleExpression(
        String operator,
        List<Argument> arguments,
        String sumSource,
        String sumWhere,
        BigDecimal sumEmptyResult,
        int scale,
        RoundingMode roundingMode
) {
    public sealed interface Argument permits Argument.FieldArgument, Argument.ConstantArgument {
        record FieldArgument(FieldRef ref) implements Argument {
        }

        record ConstantArgument(BigDecimal value) implements Argument {
        }
    }

    public BigDecimal apply(List<BigDecimal> resolvedArguments, BigDecimal sumRelatedResult) {
        BigDecimal raw = switch (operator) {
            case "ADD" -> resolvedArguments.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "SUBTRACT" -> resolvedArguments.stream().reduce((a, b) -> a.subtract(b)).orElse(BigDecimal.ZERO);
            case "MULTIPLY" -> resolvedArguments.stream().reduce(BigDecimal.ONE, BigDecimal::multiply);
            case "DIVIDE" -> {
                BigDecimal result = resolvedArguments.get(0);
                for (int i = 1; i < resolvedArguments.size(); i++) {
                    BigDecimal divisor = resolvedArguments.get(i);
                    if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("Division by zero rejects evaluation");
                    }
                    result = result.divide(divisor, Math.max(scale, 10), roundingMode);
                }
                yield result;
            }
            case "SUM_RELATED" -> sumRelatedResult;
            default -> throw new IllegalStateException("Unsupported operator '" + operator + "'");
        };
        return raw.setScale(scale, roundingMode);
    }
}
