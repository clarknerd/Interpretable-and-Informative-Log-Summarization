package sql_query_regularization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import boolean_formula_entities.BooleanLiteral;
import boolean_formula_entities.BooleanNormalClause;
import boolean_formula_entities.DisjunctiveNormalForm;
import boolean_formula_entities.FixedOrderExpression;
import net.sf.jsqlparser.expression.AllComparisonExpression;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.InverseExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.Union;

/**
 * a query without any sub-query nested in its selection predicates it will need
 * to first do DNFUNIONTransform
 * 
 * note that here query MINUS operation is not implemented in JSQLparser hence
 * we just use UNION instead
 * 
 * input: any Select query whose where clause has DNF normalized
 * 
 * @author tingxie
 *
 */
public class PredicateNestingCoalescer {
	private static final Column c = new Column(new Table(), "Multiplicity");
	private static final BooleanLiteral minusMark;
	static{
		EqualsTo eq=new EqualsTo();
		eq.setLeftExpression(c);
		eq.setRightExpression(new LongValue("-1"));		
		minusMark=new BooleanLiteral(new FixedOrderExpression(eq));
	}

	public static SelectBody predicateNestingCoalesceSelectBody(SelectBody body, boolean ignoreAggregate) {
		//need to do DNFUNION Transformer first
		body=DNFUNIONTransformer.UnionTransformSelectBody(body);
      // System.out.println("after DNFUnionTransform: "+body);
		if (body instanceof PlainSelect)
			return predicateNestingCoalescePlainSelect((PlainSelect) body, true);
		else {
			Union u = (Union) body;
			@SuppressWarnings("unchecked")
			List<PlainSelect> plist = u.getPlainSelects();
			List<PlainSelect> newplist = new ArrayList<PlainSelect>();
			for (PlainSelect ps : plist) {
				SelectBody b = predicateNestingCoalescePlainSelect(ps, true);
				if (b instanceof PlainSelect)
					newplist.add((PlainSelect) b);
				else {
					Union uu = (Union) b;
					@SuppressWarnings("unchecked")
					List<PlainSelect> pslist=uu.getPlainSelects();
					newplist.addAll(pslist);
				}
			}
			u.setPlainSelects(newplist);
			return u;
		}
	}

	private static SelectBody predicateNestingCoalescePlainSelect(PlainSelect ps, boolean ignoreAggregate) {
		// ignore having
		// search through where clause since this query has already been DNF
		// normalized
		Expression where = ps.getWhere();

		if (where != null) {
			// here we know that where clause must be conjunctive clause
			DisjunctiveNormalForm dnf = DisjunctiveNormalForm.DNFDecomposition(where);
			if (dnf.getDNFSize() == 1) {
				BooleanNormalClause clause = dnf.getContent().iterator().next();
				Iterator<BooleanLiteral> it = clause.getContent().iterator();
				
				while (it.hasNext()) {
					BooleanLiteral lit = it.next();
					// first regularize any possible sub-queries in where clause
					ExistsExpression exists = convertExistsFromBooleanLiteral(lit);

					// if sub-select exists
					if (exists != null) {
						PlainSelect host=QueryToolBox.copyPlainSelect(ps);
						SubSelect sub = (SubSelect) exists.getRightExpression();
						// remove this literal first
						it.remove();
						//regularize exists first
						sub.setSelectBody(predicateNestingCoalesceSelectBody(sub.getSelectBody(), true));
						//check if the sub-select body contains aggregation or not
						SelectBody body=sub.getSelectBody();
						boolean pass=true;
						if(body instanceof PlainSelect){
							PlainSelect pps=(PlainSelect) body;
							if(QueryToolBox.ifContainAggregate(pps)){
								if(ignoreAggregate)
								pass=true;
								else
								pass=false;
							}
						}
						else{
							Union u=(Union) body;
							@SuppressWarnings("unchecked")
							List<PlainSelect> plist=u.getPlainSelects();
							for(PlainSelect pps: plist){
								if(QueryToolBox.ifContainAggregate(pps)){
									if(ignoreAggregate)
									pass=true;
									else
									pass=false;
									
									break;
								}
							}
						}
						//if there is no aggregation in Exists

						if(pass){
							// update where clause of host
							if (!clause.getContent().isEmpty())
								host.setWhere(clause.reformExpression());
							else
								host.setWhere(null);

							// for case of EXISTS
							if (!exists.isNot()) {
								//regularize host 
								SelectBody regularizedHost=predicateNestingCoalescePlainSelect(host, ignoreAggregate);

								if(regularizedHost instanceof PlainSelect){
									PlainSelect p=(PlainSelect) regularizedHost;
									Join j = new Join();
									j.setRightItem(sub);
									//make it a simple cross product
									j.setSimple(true);
									if (p.getJoins() == null) {
										p.setJoins(new ArrayList<Join>());
									}
									@SuppressWarnings("unchecked")
									List<Join> joins=p.getJoins();
									joins.add(j);
									return p;
								}
								else {
									Union u=(Union) regularizedHost;
									@SuppressWarnings("unchecked")
									List<PlainSelect> plist=u.getPlainSelects();
									for (PlainSelect p:plist){
										Join j = new Join();
										j.setRightItem(sub);
										//make it a simple cross product
										j.setSimple(true);
										if (p.getJoins() == null) {
											p.setJoins(new ArrayList<Join>());
										}
										@SuppressWarnings("unchecked")
										List<Join> joins=p.getJoins();
										joins.add(j);
									}
									return u;
								}
							}
							// for case of NOT EXISTS
							else {
								// negate previous NOT EXISTS into EXISTS and add it back
								exists.setNot(false);
								// pre-filtered query pfq
								PlainSelect pfq = QueryToolBox.copyPlainSelect(host);
								// complement-filtered query cfq
								PlainSelect cfq= QueryToolBox.copyPlainSelect(host);
								if(cfq.getWhere()!=null){
									cfq.setWhere(new AndExpression(cfq.getWhere(),exists));
								}
								else{
									cfq.setWhere(exists);	
								}
								SelectBody regularizedpfq=predicateNestingCoalescePlainSelect(pfq, ignoreAggregate);
								SelectBody regularizedcfq=predicateNestingCoalescePlainSelect(cfq, ignoreAggregate);
								//prepare result
								Union u=new Union();
								List<PlainSelect> plist=new ArrayList<PlainSelect>();
								//add in pfq first
								if(regularizedpfq instanceof PlainSelect){
									plist.add((PlainSelect) regularizedpfq);
								}
								else {
									Union uu=(Union) regularizedpfq;
									@SuppressWarnings("unchecked")
									List<PlainSelect> pslist=uu.getPlainSelects();
									plist.addAll(pslist);
								}

								//add in cfq
								if(regularizedcfq instanceof PlainSelect){
									PlainSelect pcfq=(PlainSelect) regularizedcfq;
									//add minus mark in
									pcfq.setWhere(addInMinusMark(pcfq.getWhere()));
									plist.add(pcfq);
								}
								else {
									Union uu=(Union) regularizedcfq;

									@SuppressWarnings("unchecked")
									List<PlainSelect> pplist=uu.getPlainSelects();
									for (PlainSelect pcfq: pplist){

										//add mark in
										pcfq.setWhere(addInMinusMark(pcfq.getWhere()));									
										plist.add(pcfq);
									}
								}
								u.setPlainSelects(plist);
								return u;
							}
						}
						//if there is aggregation in Exists, then just add it back
						else
						{
							// update where clause of host
							if (!clause.getContent().isEmpty())
								host.setWhere(clause.reformExpression());
							else
								host.setWhere(null);
							
							//regularize the rest of the query besides this exists expression
							SelectBody regularizedHost=predicateNestingCoalescePlainSelect(host, ignoreAggregate);
							//add this exists expression back
							if(regularizedHost instanceof PlainSelect){
								PlainSelect pps=(PlainSelect) regularizedHost;
								if(pps.getWhere()==null)
									pps.setWhere(exists);
								else
									pps.setWhere(new AndExpression(pps.getWhere(),exists));	
								return pps;
							}
							else{
								Union u= (Union) regularizedHost;
								@SuppressWarnings("unchecked")
								List<PlainSelect> plist=u.getPlainSelects();
								for(PlainSelect pps: plist){
									if(pps.getWhere()==null)
										pps.setWhere(exists);
									else
										pps.setWhere(new AndExpression(pps.getWhere(),exists));	
								}
								return u;
							}
						}
					}
				}
			}else {
				System.out.println(
						"error found when doing predicate nesting coalesce, where clause must be purely conjunctive!");
			}
		}
		//search through it from items for sub-selects
		FromItem from=ps.getFromItem();
		predicateNestingCoalesceFromItem(from);

		//search through joins
		@SuppressWarnings("unchecked")
		List<Join> joins=ps.getJoins();
		if(joins!=null){
			for(Join j: joins){
				from=j.getRightItem();
				predicateNestingCoalesceFromItem(from);
			}
		}
		return ps;
	}

	private static void predicateNestingCoalesceFromItem(FromItem from){
		if(from instanceof SubSelect){
			SubSelect sub=(SubSelect) from;
			sub.setSelectBody(predicateNestingCoalesceSelectBody(sub.getSelectBody(), true));
		}
		else if(from instanceof SubJoin){
			SubJoin sub=(SubJoin) from;
			FromItem left=sub.getLeft();
			FromItem right=sub.getJoin().getRightItem();
			predicateNestingCoalesceFromItem(left);
			predicateNestingCoalesceFromItem(right);
		}

	}
	
	/**
	 * add minus mark in
	 * @param exp
	 * @return
	 */
	private static Expression addInMinusMark(Expression exp){
		if(exp!=null){
			DisjunctiveNormalForm form=DisjunctiveNormalForm.DNFDecomposition(exp);
			if(form.getDNFSize()>1){
				System.out.println("please check should not be more than one conjunctive clause in Predicate Nesting Coalescer"+exp);
				return null;
			}
			else {
				BooleanNormalClause clause=form.getContent().iterator().next();
				HashSet<BooleanLiteral> content=clause.getContent();
				Iterator<BooleanLiteral> it=content.iterator();
				int count=0;
				while(it.hasNext()){
					BooleanLiteral lit=it.next();
					if(lit.equals(minusMark)){
						count++;
						it.remove();
					}
				}

				//if even number of minus
				if (count%2==0){
					content.add(minusMark);
				}

				if(content.isEmpty())
					return null;
				else
					return clause.reformExpression();
			}
		}
		else {
			return minusMark.getExpression().getExpression();
		}
	}

	/**
	 * regularize every predicate with sub-query into equivalent Exists
	 * GroupBy without Aggregate/DISTINCT do not influence the result if removed
	 * TOP/LIMIT/ORDERBY are redundant  
	 * Expression
	 * 
	 * @param lit
	 * @return
	 */
	private static ExistsExpression convertExistsFromBooleanLiteral(BooleanLiteral lit) {
		Expression exp = lit.getExpression().getExpression();
		if (exp instanceof ExistsExpression) {
			ExistsExpression exist=(ExistsExpression) exp;
			exist=QueryToolBox.cleanExists(exist);
			//do predicate nesting coalesce to the exists
			SubSelect sub=(SubSelect) exist.getRightExpression();
			SelectBody body=sub.getSelectBody();
			sub.setSelectBody(PredicateNestingCoalescer.predicateNestingCoalesceSelectBody(body, true));
			return exist;
		} 
		else if (exp instanceof InExpression){
			InExpression inexp = (InExpression) exp;
			Expression left = inexp.getLeftExpression();
			ItemsList ilist = inexp.getItemsList();

			if (ilist instanceof SubSelect) {
				SubSelect sub = (SubSelect) ilist;

				if (!inexp.isNot()) {
					AnyComparisonExpression any = new AnyComparisonExpression(sub);
					EqualsTo eq = new EqualsTo();
					eq.setLeftExpression(left);
					eq.setRightExpression(any);
					return createExistsFromBinary(eq);
				} else {
					AllComparisonExpression all = new AllComparisonExpression(sub);
					NotEqualsTo neq = new NotEqualsTo();
					neq.setLeftExpression(left);
					neq.setRightExpression(all);
					return createExistsFromBinary(neq);
				}
			} else if (left instanceof SubSelect) {
				System.out.println(
						"do not know how to deal with InExpression with subquery on left side! should already be translated into corresponding OrExpressions, please check"
								+ lit);
				return null;
			} else
				return null;
		} 
		else if (exp instanceof EqualsTo || exp instanceof NotEqualsTo || exp instanceof MinorThan
				|| exp instanceof MinorThanEquals || exp instanceof GreaterThan || exp instanceof GreaterThanEquals) {
			BinaryExpression bexp = (BinaryExpression) exp;
			Expression left = bexp.getLeftExpression();
			Expression right = bexp.getRightExpression();
			boolean leftmark, rightmark;
			if (left instanceof AnyComparisonExpression || left instanceof AllComparisonExpression
					|| left instanceof SubSelect)
				leftmark = true;
			else
				leftmark = false;

			if (right instanceof AnyComparisonExpression || right instanceof AllComparisonExpression
					|| right instanceof SubSelect)
				rightmark = true;
			else
				rightmark = false;
			// this means two sides are both sub-select
			if (leftmark && rightmark) {
				bexp.setLeftExpression(left);
				// treated as any
				AnyComparisonExpression any = new AnyComparisonExpression((SubSelect) right);
				bexp.setRightExpression(any);
				return createExistsFromBinary(bexp);
			} else if (leftmark) {
				if (bexp instanceof EqualsTo || bexp instanceof NotEqualsTo) {
					bexp.setLeftExpression(right);
					bexp.setRightExpression(left);
					return createExistsFromBinary(bexp);
				} else if (bexp instanceof MinorThan) {
					GreaterThan gt = new GreaterThan();
					gt.setLeftExpression(right);
					gt.setRightExpression(left);
					return createExistsFromBinary(gt);
				} else if (bexp instanceof MinorThanEquals) {
					GreaterThanEquals gte = new GreaterThanEquals();
					gte.setLeftExpression(right);
					gte.setRightExpression(left);
					return createExistsFromBinary(gte);
				} else if (bexp instanceof GreaterThan) {
					MinorThan mt = new MinorThan();
					mt.setLeftExpression(right);
					mt.setRightExpression(left);
					return createExistsFromBinary(mt);
				} else {
					MinorThanEquals mte = new MinorThanEquals();
					mte.setLeftExpression(right);
					mte.setRightExpression(left);
					return createExistsFromBinary(mte);
				}
			} else if (rightmark) {
				return createExistsFromBinary(bexp);
			} else
				return null;
		} else if (exp instanceof Parenthesis) {
			Parenthesis p = (Parenthesis) exp;
			if (p.getExpression() != null) {
				ExistsExpression exists = convertExistsFromBooleanLiteral(
						new BooleanLiteral(new FixedOrderExpression(p.getExpression())));
				if (p.isNot() && exists != null)
					exists.setNot(!exists.isNot());
				return exists;
			} else
				return null;
		} else if (exp instanceof InverseExpression) {
			InverseExpression p = (InverseExpression) exp;
			if (p.getExpression() != null) {
				ExistsExpression exists = convertExistsFromBooleanLiteral(new BooleanLiteral(new FixedOrderExpression(p.getExpression())));
				if (exists != null)
					exists.setNot(!exists.isNot());
				return exists;
			} else
				return null;
		} else
			return null;
	}

	/**
	 * it requires that attribute must be on left side of input and sub-query
	 * with ALL or ANY modifier must be on right side of input
	 * 
	 * @return
	 */
	private static ExistsExpression createExistsFromBinary(BinaryExpression bexp) {

		Expression right = bexp.getRightExpression();
		ExistsExpression exists = new ExistsExpression();
		
		if (right instanceof AllComparisonExpression) {
			SubSelect sub = ((AllComparisonExpression) right).getSubSelect();
			SelectBody body=PredicateNestingCoalescer.predicateNestingCoalesceSelectBody(sub.getSelectBody(), true);
			adjustSelectBodyConsiderNotExists(body,bexp);
			sub.setSelectBody(body);
			exists.setNot(true); 
			exists.setRightExpression(sub);
		} else if (right instanceof AnyComparisonExpression) {
			SubSelect sub = ((AnyComparisonExpression) right).getSubSelect();
			SelectBody body=PredicateNestingCoalescer.predicateNestingCoalesceSelectBody(sub.getSelectBody(), true);
			adjustSelectBodyConsiderExists(body,bexp);
			sub.setSelectBody(body);
			exists.setNot(false); 
			exists.setRightExpression(sub);
		}
		// treated exactly as ANY comparison
		else if (right instanceof SubSelect) {
			SubSelect sub = (SubSelect) right;
			SelectBody body=PredicateNestingCoalescer.predicateNestingCoalesceSelectBody(sub.getSelectBody(), true);
			adjustSelectBodyConsiderExists(body,bexp);
			sub.setSelectBody(body);
			exists.setNot(false); 
			exists.setRightExpression(sub);
		} else {
			System.out.println(
					"second input for function createExistsFromBinary should be either ANYcomparison or ALLcomparison!"
							+ bexp);
			return null;
		}		
		return QueryToolBox.cleanExists(exists);
	}

	private static void adjustPlainSelectConsiderExists(PlainSelect ps,BinaryExpression bexp){
		Expression left = bexp.getLeftExpression();
		Expression innerright = QueryToolBox.extractFirstSelectItemFromPlainSelectForBinaryOperation(ps);
		BinaryExpression addon = null;
		try {
			addon = bexp.getClass().newInstance();
			if(bexp.isNot())
				addon.setNot();
			addon.setLeftExpression(left);
			addon.setRightExpression(innerright);
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		Expression mergedWhere;
		if (ps.getWhere() != null)
			mergedWhere = new AndExpression(addon, ps.getWhere());
		else
			mergedWhere = addon;
		ps.setWhere(mergedWhere);
	}
	
	private static void adjustSelectBodyConsiderExists(SelectBody body,BinaryExpression bexp){
		if(body instanceof PlainSelect){
			PlainSelect ps=(PlainSelect) body;
			adjustPlainSelectConsiderExists(ps,bexp);
		}
		else {
			Union u=(Union) body;
			@SuppressWarnings("unchecked")
			List<PlainSelect> plist=u.getPlainSelects();
			for (PlainSelect ps:plist)
			adjustPlainSelectConsiderExists(ps,bexp);
		}
	}
	
	private static void adjustSelectBodyConsiderNotExists(SelectBody body,BinaryExpression bexp){
		if(body instanceof PlainSelect){
			PlainSelect ps=(PlainSelect) body;
			adjustPlainSelectConsiderNotExists(ps,bexp);
		}
		else {
			Union u=(Union) body;
			@SuppressWarnings("unchecked")
			List<PlainSelect> plist=u.getPlainSelects();
			for (PlainSelect ps:plist)
			adjustPlainSelectConsiderNotExists(ps,bexp);
		}
	}

	private static void adjustPlainSelectConsiderNotExists(PlainSelect ps,BinaryExpression bexp){
		Expression innerright = QueryToolBox.extractFirstSelectItemFromPlainSelectForBinaryOperation(ps);
		Expression left=bexp.getLeftExpression();

		BinaryExpression addon;
		// case of A=ALL B -> NOT EXISTS A!=B
		if (bexp instanceof EqualsTo){
			NotEqualsTo neq=new NotEqualsTo();
			neq.setLeftExpression(left);
			neq.setRightExpression(innerright);
			addon = neq;			
		}
		// case of A!=ALL B -> NOT EXISTS A=B
		else if (bexp instanceof NotEqualsTo){
			EqualsTo eq=new EqualsTo();
			eq.setLeftExpression(left);
			eq.setRightExpression(innerright);
			addon = eq;
		}
		// case of A>ALL B -> NOT EXISTS A<=B
		else if (bexp instanceof GreaterThan){
			MinorThanEquals mte=new MinorThanEquals();
			mte.setLeftExpression(left);
			mte.setRightExpression(innerright);
			addon = mte;			
		}
		// case of A>=ALL B -> NOT EXISTS A<B
		else if (bexp instanceof GreaterThanEquals){
			MinorThan mt=new MinorThan();
			mt.setLeftExpression(left);
			mt.setRightExpression(innerright);
			addon = mt;
		}
		// case of A<ALL B -> NOT EXISTS A>=B
		else if (bexp instanceof MinorThan){
			GreaterThanEquals gt=new GreaterThanEquals();
			gt.setLeftExpression(left);
			gt.setRightExpression(innerright);
			addon = gt;
		}
		else{
			GreaterThan gt=new GreaterThan();
			gt.setLeftExpression(left);
			gt.setRightExpression(innerright);
			addon = gt;
		}
		Expression mergedWhere;
		if (ps.getWhere() != null)
			mergedWhere = new AndExpression(addon, ps.getWhere());
		else
			mergedWhere = addon;
		ps.setWhere(mergedWhere);
	}
}
