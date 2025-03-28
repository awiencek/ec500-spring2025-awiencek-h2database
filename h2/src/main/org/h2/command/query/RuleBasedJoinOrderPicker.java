package org.h2.command.query;
 
import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
 
/**
 * Determines the best join order by following rules rather than considering every possible permutation.
 */
public class RuleBasedJoinOrderPicker {
 final SessionLocal session;
 final TableFilter[] filters;
 
 public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
  this.session = session;
  this.filters = filters;
 }
 
 public TableFilter[] bestOrder() {
        // Sort the filters based on the row count of the tables they represent
        Arrays.sort(filters, new Comparator<TableFilter>() {
            @Override
            public int compare(TableFilter filter1, TableFilter filter2) {
                // Get the row counts for both tables
                long rowCount1 = getRowCount(filter1);
                long rowCount2 = getRowCount(filter2);

                // Compare row counts to determine the join order
                return Long.compare(rowCount1, rowCount2);
            }
        });

        // Return the filters in the sorted order (smallest to largest)
        return filters;
    }

    private long getRowCount(TableFilter filter) {
        Table table = filter.getTable();
        return table.getRowCountApproximation();
    }
}
