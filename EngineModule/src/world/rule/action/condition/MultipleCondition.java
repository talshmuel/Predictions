package world.rule.action.condition;

import exception.DivisionByZeroException;
import exception.IncompatibleAction;
import exception.IncompatibleType;
import world.rule.action.Action;
import world.rule.action.api.PropertiesToAction;

import world.rule.action.api.PropertiesToMultipleCondition;

import java.util.ArrayList;


public class MultipleCondition extends Condition {
    ArrayList<Condition> conditions;
    ArrayList<Action> thenActions;
    ArrayList<Action> elseActions;

    public enum Logic {OR, AND}

    Logic logicSign;

    public MultipleCondition(String mainEntity, String propToChangeName,
                             ArrayList<Action> thenActions, ArrayList<Action> elseActions,
                             Logic logicSign, ArrayList<Condition> conditions){
        super(mainEntity, propToChangeName, null);
        this.conditions = conditions;
        this.elseActions = elseActions;
        this.thenActions = thenActions;
        this.logicSign = logicSign;
    }

    public Boolean checkCondition(PropertiesToAction propsToChange)throws IncompatibleAction, IncompatibleType {
        switch (logicSign) {
            case OR:
                return checkORCondition((PropertiesToMultipleCondition) propsToChange);
            case AND: {
                return checkANDCondition((PropertiesToMultipleCondition) propsToChange);

            }
        }
        return false;
    }

    @Override
    public Boolean activate(PropertiesToAction propsToChange)throws DivisionByZeroException, IncompatibleAction, IncompatibleType {
        if (checkCondition(propsToChange)) {

            return activateThenActions(((PropertiesToMultipleCondition)propsToChange).getThenProps());
        }
        else if (elseActions != null)
            return activateElseActions(((PropertiesToMultipleCondition)propsToChange).getElseProps());
        else{
            return false;
        }
    }

    @Override
    public ArrayList<Action> getThenActions() {
        return thenActions;
    }

    @Override
    public ArrayList<Action> getElseActions() {
        return elseActions;
    }

    public ArrayList<Condition> getConditions() {
        return conditions;
    }

    @Override
    public Boolean activateThenActions(ArrayList<PropertiesToAction> props)throws DivisionByZeroException, IncompatibleAction, IncompatibleType {
        boolean kill=false;
        int len = thenActions.size();
        for(int i=0; i<len;i++){
            if(thenActions.get(i).activate(props.get(i)))
                kill = true;
        }
        return kill;
    }

    @Override
    public Boolean activateElseActions(ArrayList<PropertiesToAction> props)throws DivisionByZeroException ,IncompatibleAction, IncompatibleType {
        boolean kill=false;
        int len = elseActions.size();
        for(int i=0; i<len;i++){
            if(elseActions.get(i).activate(props.get(i)))
                kill = true;
        }
        return kill;
    }

    Boolean checkORCondition(PropertiesToMultipleCondition props)throws IncompatibleAction, IncompatibleType{
        boolean res = false;
        int len = conditions.size();
        for(int i=0; i<len; i++){
            if(conditions.get(i).checkCondition(props.getConditionsProp().get(i)))
                res = true;
        }
        return res;
    }
    Boolean checkANDCondition(PropertiesToMultipleCondition props)throws IncompatibleAction, IncompatibleType{
        boolean res = true;
        int len = conditions.size();
        for(int i=0; i<len; i++){
            if(!conditions.get(i).checkCondition(props.getConditionsProp().get(i)))
                res = false;
        }
        return res;
    }
}