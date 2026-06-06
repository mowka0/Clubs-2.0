import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { ActivityTypeOptions } from './CreateActivityPicker';
import { ClubPickerList, type ClubPickerOption } from './ClubPickerModal';
import type { ActivityType } from '../../api/activities';

interface CreateActivityFlowProps {
  /** Whether the creation flow is open. */
  open: boolean;
  /** Clubs the user organizes — the flow only navigates within these. */
  organizerClubs: ClubPickerOption[];
  /**
   * When set, the club picker is skipped entirely — after choosing the type
   * the flow navigates straight into this club. Used when the FAB is tapped
   * from a club the user is currently viewing (and organizes).
   */
  presetClubId?: string | null;
  /** Closes the whole flow (resets internal step state). */
  onClose: () => void;
}

type Step = 'type' | 'club';

function createRoute(clubId: string, type: ActivityType): string {
  return type === 'event'
    ? `/clubs/${clubId}/events/new`
    : `/clubs/${clubId}/skladchina/new`;
}

/**
 * Controller for the global "тип → клуб → форма" creation flow.
 *
 * Renders a SINGLE Modal whose body swaps by `step`. Step 'type' picks the
 * activity type; step 'club' picks the target club when the user organizes
 * multiple. A single Modal is required: rendering a separate Modal per step
 * made the closing one tear down the shared portal/scroll-lock overlay the
 * opening one had just mounted, collapsing the second instantly.
 *
 * After resolving type + club, navigate to the per-club create route
 * (CreateEventPage / CreateSkladchinaPage read :id).
 */
export const CreateActivityFlow: FC<CreateActivityFlowProps> = ({
  open,
  organizerClubs,
  presetClubId,
  onClose,
}) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const [step, setStep] = useState<Step>('type');
  const [pendingType, setPendingType] = useState<ActivityType | null>(null);

  const dismiss = () => {
    setStep('type');
    setPendingType(null);
    onClose();
  };

  const goToCreate = (clubId: string, type: ActivityType) => {
    setStep('type');
    setPendingType(null);
    onClose();
    navigate(createRoute(clubId, type));
  };

  const handlePickType = (type: ActivityType) => {
    haptic.impact('medium');
    // Current-club context (FAB on a club page) → skip the picker entirely.
    if (presetClubId) {
      goToCreate(presetClubId, type);
      return;
    }
    if (organizerClubs.length === 1) {
      goToCreate(organizerClubs[0]!.id, type);
      return;
    }
    // Multiple clubs: stay in the SAME Modal, just swap content to the club step.
    setPendingType(type);
    setStep('club');
  };

  const handlePickClub = (clubId: string) => {
    if (!pendingType) return;
    haptic.impact('medium');
    goToCreate(clubId, pendingType);
  };

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      haptic.impact('light');
      dismiss();
    }
  };

  return (
    <Modal open={open} onOpenChange={handleOpenChange}>
      {step === 'type' ? (
        <ActivityTypeOptions onPick={handlePickType} />
      ) : (
        <ClubPickerList clubs={organizerClubs} onPick={handlePickClub} />
      )}
    </Modal>
  );
};
